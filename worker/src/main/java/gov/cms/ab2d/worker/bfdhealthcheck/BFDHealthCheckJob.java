package gov.cms.ab2d.worker.bfdhealthcheck;


import gov.cms.ab2d.fhir.Versions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
public class BFDHealthCheckJob extends QuartzJobBean {

    private BFDHealthCheck bfdHealthCheck;

    @Override
    protected void executeInternal(JobExecutionContext jobExecutionContext) {
        bfdHealthCheck.checkBFDHealth(Versions.FhirVersions.STU3);
        // TODO - do the check when we can be sure it's reliable
        // bfdHealthCheck.checkBFDHealth(Versions.FhirVersions.R4);
    }
}
