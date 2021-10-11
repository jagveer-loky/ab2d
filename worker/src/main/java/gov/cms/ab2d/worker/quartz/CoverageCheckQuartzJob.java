package gov.cms.ab2d.worker.quartz;

import gov.cms.ab2d.common.service.PropertiesService;
import gov.cms.ab2d.eventlogger.LogManager;
import gov.cms.ab2d.worker.processor.coverage.CoverageDriver;
import gov.cms.ab2d.worker.processor.coverage.CoverageVerificationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.scheduling.quartz.QuartzJobBean;

@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
public class CoverageCheckQuartzJob extends QuartzJobBean {

    private final LogManager logManager;
    private final CoverageDriver driver;
    private final PropertiesService propertiesService;

    @Override
    protected void executeInternal(JobExecutionContext jobExecutionContext) throws JobExecutionException {

        if (propertiesService.isInMaintenanceMode()) {
            log.info("Skipping enrollment verification because AB2D is already in maintenance mode");
        }

        try {
            driver.verifyCoverage();
        } catch (CoverageVerificationException exception) {
            log.error("coverage is invalid or not able to be verified {}", exception.getAlertMessage());

            // todo temporarily use null until testing is done
            logManager.alert("Coverage verification failed:\n" + exception.getAlertMessage(), null);

            throw new JobExecutionException(exception);
        } catch (Exception exception) {
            log.error("unexpected failure attempting to verify coverage", exception);

            // todo temporarily use null until testing is done
            logManager.alert("could not verify coverage due to " + exception.getClass()
                    + ":\n" + exception.getMessage(), null);

            throw new JobExecutionException(exception);
        }
    }
}