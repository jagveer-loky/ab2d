package gov.cms.ab2d.worker.processor;

import gov.cms.ab2d.common.model.Job;
import gov.cms.ab2d.common.model.JobOutput;

import java.nio.file.Path;
import java.util.List;

/**
 * Execute a job from start to finish. Where the {@link JobProcessor} handles the high level
 * phases of a {@link Job}, this class handles the nuts and bolts. For example, loading enrollment,
 * queueing requests to BFD, processing request results, etc.
 */
public interface ContractProcessor {
    List<JobOutput> process(Path outputDirPath, Job job);
}
