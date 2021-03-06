package com.spiddekauga.appengine.pipeline;

import com.google.api.services.bigquery.Bigquery.Jobs.Insert;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobConfiguration;
import com.google.api.services.bigquery.model.JobConfigurationLoad;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.mapreduce.impl.BigQueryConstants;
import com.google.appengine.tools.mapreduce.impl.util.SerializableValue;
import com.google.appengine.tools.pipeline.ImmediateValue;
import com.google.appengine.tools.pipeline.Job1;
import com.google.appengine.tools.pipeline.PromisedValue;
import com.google.appengine.tools.pipeline.Value;
import com.google.common.base.Optional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * A pipeline job that manages the lifecycle of a bigquery load {@link Job}. It triggers, polls for
 * status and retries or cleans up the files based on the status of the load job.
 */
@SuppressWarnings("javadoc")
final class BigQueryLoadFileSetJob extends Job1<BigQueryLoadJobReference, Integer> {
private static final long serialVersionUID = 6405179047095627345L;
private static final Logger log = Logger.getLogger(BigQueryLoadFileSetJob.class.getName());
private final String mDataset;
private final String mTableName;
private final String mProjectId;
private final List<GcsFilename> mFileSet;
private final SerializableValue<TableSchema> mSchema;

/**
 * @param dataset the name of the BigQuery mDataset.
 * @param tableName name of the BigQuery table to load data.
 * @param projectId BigQuery project Id.
 * @param fileSet list of the GCS files to load.
 * @param schema wrapper around a non-serializable {@link TableSchema} object.
 */
BigQueryLoadFileSetJob(String dataset, String tableName, String projectId, List<GcsFilename> fileSet, SerializableValue<TableSchema> schema) {
	mDataset = dataset;
	mTableName = tableName;
	mProjectId = projectId;
	mFileSet = fileSet;
	mSchema = schema;
}

@Override
public Value<BigQueryLoadJobReference> run(Integer retryCount) throws Exception {
	if (retryCount >= BigQueryConstants.MAX_RETRIES) {
		throw new RuntimeException("Unable to load the files into BigQuery = " + mFileSet
				+ " after max number of retries. Check log for more details.");
	}
	ImmediateValue<BigQueryLoadJobReference> jobTrigger = immediate(triggerBigQueryLoadJob());

	String queue = Optional.fromNullable(getOnQueue()).or("default");

	PromisedValue<String> jobStatus = newPromise();
	futureCall(new BigQueryLoadPollJob(jobStatus.getHandle()), jobTrigger, onQueue(queue));

	return futureCall(new RetryLoadOrCleanupJob(mDataset, mTableName, mProjectId, mFileSet, mSchema), jobTrigger, immediate(retryCount),
			waitFor(jobStatus), onQueue(queue));
}

/**
 * Triggers a bigquery load {@link Job} request and returns the job Id for the same.
 */
private BigQueryLoadJobReference triggerBigQueryLoadJob() {
	Job job = createJob();
	// Set up Bigquery Insert
	try {
		Insert insert = BigQueryLoadGoogleCloudStorageFilesJob.getBigquery().jobs().insert(mProjectId, job);
		Job executedJob = insert.execute();
		log.info("Triggered the bigQuery load job for files " + mFileSet + " . Job Id = " + executedJob.getId());
		return new BigQueryLoadJobReference(mProjectId, executedJob.getJobReference());
	} catch (IOException e) {
		log.warning("Error in triggering BigQuery load job for files " + mFileSet);
		throw new RuntimeException("Error in triggering BigQuery load job for files " + mFileSet, e);
	}
}

/**
 * Create a {@link Job} instance for the specified files and bigquery {@link TableSchema} with
 * default settings.
 */
private Job createJob() {
	Job job = new Job();
	JobConfiguration jobConfig = new JobConfiguration();
	JobConfigurationLoad loadConfig = new JobConfigurationLoad();
	jobConfig.setLoad(loadConfig);
	job.setConfiguration(jobConfig);

	loadConfig.setAllowQuotedNewlines(false);
	loadConfig.setSourceFormat("NEWLINE_DELIMITED_JSON");
	loadConfig.setWriteDisposition("WRITE_APPEND");

	List<String> sources = new ArrayList<String>();
	for (GcsFilename file : mFileSet) {
		sources.add(getFileUri(file));
	}

	loadConfig.setSourceUris(sources);

	TableReference tableRef = new TableReference();
	tableRef.setDatasetId(mDataset);
	tableRef.setTableId(mTableName);
	tableRef.setProjectId(mProjectId);
	loadConfig.setDestinationTable(tableRef);
	loadConfig.setSchema(mSchema.getValue());

	// @formatter:off
//		log.info("Create Job"
//				+ "\nDataset: " + mDataset
//				+ "\nTable: " + mTableName
//				+ "\nProject: " + mProjectId
//				+ "\nSources: " + sources
//				+ "\nSchema: " + loadConfig.getSchema());
		// @formatter:on

	return job;
}

// TODO : Make it a part of a util class ?
private String getFileUri(GcsFilename fileName) {
	return "gs://" + fileName.getBucketName() + "/" + fileName.getObjectName();
}
}
