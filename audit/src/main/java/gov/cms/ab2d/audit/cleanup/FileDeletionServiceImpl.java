package gov.cms.ab2d.audit.cleanup;

import gov.cms.ab2d.common.model.Job;
import gov.cms.ab2d.common.service.JobService;
import gov.cms.ab2d.common.service.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Component
public class FileDeletionServiceImpl implements FileDeletionService {

    @Value("${efs.mount}")
    private String efsMount;

    @Value("${audit.files.ttl.hours}")
    private int auditFilesTTLHours;

    @Autowired
    private JobService jobService;

    private static final String FILE_EXTENSION = ".ndjson";

    private static Set<String> disallowedDirectories = Set.of("/bin", "/boot", "/dev", "/etc", "/home", "/lib",
            "/opt", "/root", "/sbin", "/sys", "/usr", "/Applications", "/Library", "/Network", "/System", "/Users", "/Volumes");

    /**
     * Delete all files that are in the efs mount with the .ndjson extension that are older than the TTL variable
     */
    @Override
    public void deleteFiles() {
        log.info("File deletion service kicked off");
        if (!efsMount.startsWith("/")) {
            throw new EFSMountFormatException("EFS Mount must start with a /");
        }

        if (efsMount.length() < 5) {
            throw new EFSMountFormatException("EFS mount must be at least 5 characters");
        }

        for (String directory : disallowedDirectories) {
            if (efsMount.startsWith(directory)) {
                throw new EFSMountFormatException("EFS mount must not start with a directory that contains important files");
            }
        }

        try (Stream<Path> walk = Files.walk(Paths.get(efsMount), FileVisitOption.FOLLOW_LINKS)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String jobUuid = new File(path.toUri()).getParentFile().getName();
                    Job job = null;
                    try {
                        job = jobService.getJobByJobUuid(jobUuid);
                    } catch (ResourceNotFoundException e) {
                        log.trace("No job connected to directory {}", path); // Put a log statement here to make PMD happy
                    }
                    Instant deleteCheckTime = null;
                    if (job != null && job.getStatus().isFinished()) {
                        deleteCheckTime = job.getCompletedAt().toInstant();
                    } else if (job == null) {
                        FileTime creationTime = (FileTime) Files.getAttribute(path, "creationTime");
                        deleteCheckTime = creationTime.toInstant();
                    }

                    if (deleteCheckTime != null) {
                        if (deleteCheckTime.isBefore(Instant.now().minus(auditFilesTTLHours, ChronoUnit.HOURS)) &&
                                path.toString().endsWith(FILE_EXTENSION.toLowerCase())) {
                            Files.delete(path);
                            log.info("Deleted file {}", path);
                        } else {
                            logFileNotEligibleForDeletion(path);
                        }
                    } else {
                        logFileNotEligibleForDeletion(path);
                    }
                } catch (IOException e) {
                    log.error("Encountered exception trying to delete a file {}, moving onto next one", path, e);
                }
            });

        } catch (IOException e) {
            log.error("Encountered exception while trying to gather the list of files to delete", e);
        }
    }

    private void logFileNotEligibleForDeletion(Path path) {
        log.info("File not eligible for deletion {}", path);
    }
}
