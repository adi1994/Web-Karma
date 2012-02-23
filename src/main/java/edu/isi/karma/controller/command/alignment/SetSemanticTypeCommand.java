package edu.isi.karma.controller.command.alignment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.isi.karma.controller.command.Command;
import edu.isi.karma.controller.command.CommandException;
import edu.isi.karma.controller.update.ErrorUpdate;
import edu.isi.karma.controller.update.SemanticTypesUpdate;
import edu.isi.karma.controller.update.TagsUpdate;
import edu.isi.karma.controller.update.UpdateContainer;
import edu.isi.karma.modeling.alignment.AlignToOntology;
import edu.isi.karma.modeling.semantictypes.CRFColumnModel;
import edu.isi.karma.modeling.semantictypes.SemanticTypeUtil;
import edu.isi.karma.modeling.semantictypes.crfmodelhandler.CRFModelHandler;
import edu.isi.karma.modeling.semantictypes.crfmodelhandler.CRFModelHandler.ColumnFeature;
import edu.isi.karma.rep.HNodePath;
import edu.isi.karma.rep.Worksheet;
import edu.isi.karma.rep.metadata.TagsContainer.TagName;
import edu.isi.karma.rep.semantictypes.SemanticType;
import edu.isi.karma.rep.semantictypes.SynonymSemanticTypes;
import edu.isi.karma.view.VWorkspace;

public class SetSemanticTypeCommand extends Command {

	private SemanticType oldType;
	private SynonymSemanticTypes oldSynonymTypes;
	private final String vWorksheetId;
	private CRFColumnModel oldColumnModel;
	private final SemanticType newType;
	private final SynonymSemanticTypes newSynonymTypes;

	private final Logger logger = LoggerFactory.getLogger(this.getClass()
			.getSimpleName());

	protected SetSemanticTypeCommand(String id, String vWorksheetId,
			String hNodeId, boolean isPartOfKey, SemanticType type,
			SynonymSemanticTypes synTypes) {
		super(id);
		this.vWorksheetId = vWorksheetId;
		this.newType = type;
		this.newSynonymTypes = synTypes;
	}

	@Override
	public String getCommandName() {
		return this.getClass().getSimpleName();
	}

	@Override
	public String getTitle() {
		return "Set Semantic Type";
	}

	@Override
	public String getDescription() {
		String domainLabel = SemanticTypeUtil.removeNamespace(newType
				.getDomain());
		String typeLabel = SemanticTypeUtil.removeNamespace(newType.getType());
		if (domainLabel.equals(""))
			return typeLabel;
		else
			return typeLabel + " of " + domainLabel;
	}

	@Override
	public CommandType getCommandType() {
		return CommandType.undoable;
	}

	@Override
	public UpdateContainer doIt(VWorkspace vWorkspace) throws CommandException {
		UpdateContainer c = new UpdateContainer();
		Worksheet worksheet = vWorkspace.getViewFactory()
				.getVWorksheet(vWorksheetId).getWorksheet();

		// Save the old SemanticType object and CRF Model for undo
		oldType = worksheet.getSemanticTypes().getSemanticTypeForHNodeId(
				newType.getHNodeId());
		oldColumnModel = worksheet.getCrfModel().getModelByHNodeId(
				newType.getHNodeId());
		oldSynonymTypes = worksheet.getSemanticTypes()
				.getSynonymTypesForHNodeId(newType.getHNodeId());

		// Update the SemanticTypes data structure for the worksheet
		worksheet.getSemanticTypes().addType(newType);

		// Update the synonym semanticTypes
		worksheet.getSemanticTypes().addSynonymTypesForHNodeId(
				newType.getHNodeId(), newSynonymTypes);

		// Find the corresponding hNodePath. Used to find examples for training
		// the CRF Model.
		HNodePath currentColumnPath = null;
		List<HNodePath> paths = worksheet.getHeaders().getAllPaths();
		for (HNodePath path : paths) {
			if (path.getLeaf().getId().equals(newType.getHNodeId())) {
				currentColumnPath = path;
				break;
			}
		}

		Map<ColumnFeature, Collection<String>> columnFeatures = new HashMap<ColumnFeature, Collection<String>>();

		// Prepare the column name for training
		String columnName = currentColumnPath.getLeaf().getColumnName();
		Collection<String> columnNameList = new ArrayList<String>();
		columnNameList.add(columnName);
		columnFeatures.put(ColumnFeature.ColumnHeaderName, columnNameList);

		// // Prepare the worksheet name for training
		// String tableName = worksheet.getTitle();
		// Collection<String> tableNameList = new ArrayList<String>();
		// tableNameList.add(tableName);
		// columnFeatures.put(ColumnFeature.TableName, tableNameList);

		// Calculating the time required for training the semantic type
		long start = System.currentTimeMillis();

		// Train the model with the new type
		ArrayList<String> trainingExamples = SemanticTypeUtil
				.getTrainingExamples(worksheet, currentColumnPath);
		boolean trainingResult = false;
		String newTypeString = "";
		if (newType.getDomain().equals("")) {
			newTypeString = newType.getType();
		} else {
			newTypeString = newType.getDomain() + "|" + newType.getType();
		}
		trainingResult = CRFModelHandler.addOrUpdateLabel(newTypeString,
				trainingExamples, columnFeatures);

		if (!trainingResult) {
			logger.error("Error occured while training CRF Model.");
		}

		logger.debug("Using type:" + newType.getDomain() + "|"
				+ newType.getType());

		long elapsedTimeMillis = System.currentTimeMillis() - start;
		float elapsedTimeSec = elapsedTimeMillis / 1000F;
		logger.info("Time required for training the semantic type: "
				+ elapsedTimeSec);

		// Add the new CRF column model for this column
		ArrayList<String> labels = new ArrayList<String>();
		ArrayList<Double> scores = new ArrayList<Double>();
		trainingResult = CRFModelHandler.predictLabelForExamples(
				trainingExamples, 4, labels, scores, null, columnFeatures);
		if (!trainingResult) {
			logger.error("Error occured while predicting labels");
		}
		CRFColumnModel newModel = new CRFColumnModel(labels, scores);
		worksheet.getCrfModel().addColumnModel(newType.getHNodeId(), newModel);

		// Identify the outliers for the column
		SemanticTypeUtil.identifyOutliers(worksheet, newTypeString,
				currentColumnPath, vWorkspace.getWorkspace().getTagsContainer()
						.getTag(TagName.Outlier), columnFeatures);

		c.add(new SemanticTypesUpdate(worksheet, vWorksheetId));

		// Get the alignment update if any
		AlignToOntology align = new AlignToOntology(worksheet, vWorkspace,
				vWorksheetId);
		try {
			align.update(c, true);
		} catch (Exception e) {
			logger.error("Error occured while setting the semantic type!", e);
			return new UpdateContainer(new ErrorUpdate(
					"Error occured while setting the semantic type!"));
		}

		c.add(new TagsUpdate());
		return c;
	}

	@Override
	public UpdateContainer undoIt(VWorkspace vWorkspace) {
		UpdateContainer c = new UpdateContainer();
		Worksheet worksheet = vWorkspace.getViewFactory()
				.getVWorksheet(vWorksheetId).getWorksheet();
		if (oldType == null) {
			worksheet.getSemanticTypes().unassignColumnSemanticType(
					newType.getHNodeId());
		} else {
			worksheet.getSemanticTypes().addType(oldType);
			worksheet.getSemanticTypes().addSynonymTypesForHNodeId(
					newType.getHNodeId(), oldSynonymTypes);
		}

		worksheet.getCrfModel().addColumnModel(newType.getHNodeId(),
				oldColumnModel);

		// Get the alignment update if any
		AlignToOntology align = new AlignToOntology(worksheet, vWorkspace,
				vWorksheetId);
		try {
			align.update(c, true);
		} catch (Exception e) {
			logger.error("Error occured while unsetting the semantic type!", e);
			return new UpdateContainer(new ErrorUpdate(
					"Error occured while unsetting the semantic type!"));
		}
		return c;
	}
}
