package gov.cms.ab2d.common.repository;

import gov.cms.ab2d.common.model.Contract;
import gov.cms.ab2d.common.model.Job;
import gov.cms.ab2d.common.model.JobStartedBy;
import gov.cms.ab2d.common.model.PdpClient;
import gov.cms.ab2d.common.model.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Job j set j.status = gov.cms.ab2d.common.model.JobStatus.CANCELLED where j.jobUuid = :jobUuid")
    void cancelJobByJobUuid(@Param("jobUuid") String jobUuid);

    Job findByJobUuid(String jobUuid);

    @Query("select j from Job j where j.pdpClient = :pdpClient and (j.status = 'IN_PROGRESS' or j.status = 'SUBMITTED')")
    List<Job> findActiveJobsByClient(PdpClient pdpClient);

    List<Job> findByContractEqualsAndStatusInAndStartedByOrderByCompletedAtDesc(
            Contract contract, List<JobStatus> statuses, JobStartedBy startedBy);

    @Query("select j from Job j where j.pdpClient = :pdpClient and j.contract = :contract and (j.status = 'IN_PROGRESS' or j.status = 'SUBMITTED')")
    List<Job> findActiveJobsByClientAndContract(PdpClient pdpClient, Contract contract);

    @Query("SELECT j.status FROM Job j WHERE j.jobUuid = :jobUuid ")
    JobStatus findJobStatus(String jobUuid);

    @Query("FROM Job j WHERE j.createdAt < :createdAt AND j.status = 'IN_PROGRESS' AND j.completedAt IS NULL ")
    List<Job> findStuckJobs(OffsetDateTime createdAt);

    @Modifying
    @Query("UPDATE Job j SET j.status = 'SUBMITTED' WHERE j.jobUuid IN :jobUuids ")
    void resetJobsToSubmittedStatus(List<String> jobUuids);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE Job j SET j.progress = :percentageCompleted WHERE j.jobUuid = :jobUuid ")
    int updatePercentageCompleted(String jobUuid, int percentageCompleted);

    @Query("SELECT COUNT(j) FROM Job j WHERE j.contract = :contract AND j.status IN :statuses")
    int countJobByContractAndStatus(Contract contract, List<JobStatus> statuses);
}
