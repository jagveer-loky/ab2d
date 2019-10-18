package gov.cms.ab2d.api.controller;

import gov.cms.ab2d.api.SpringBootApp;
import gov.cms.ab2d.api.repository.JobRepository;
import gov.cms.ab2d.domain.Job;
import gov.cms.ab2d.domain.JobStatus;
import org.hamcrest.core.Is;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static gov.cms.ab2d.api.service.JobServiceImpl.INITIAL_JOB_STATUS_MESSAGE;
import static gov.cms.ab2d.api.util.Constants.API_PREFIX;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = SpringBootApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
public class BulkDataAccessAPIIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobRepository jobRepository;

    private static final String PATIENT_EXPORT_PATH = "/Patient/$export";

    @Test
    public void testBasicPatientExport() throws Exception {
        ResultActions resultActions = this.mockMvc.perform(get(API_PREFIX + PATIENT_EXPORT_PATH).contentType(MediaType.APPLICATION_JSON))
                .andDo(print());
        Job job = jobRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).iterator().next();

        String statusUrl = "http://localhost" + API_PREFIX + "/Job/" + job.getJobID() + "/$status";

        resultActions.andExpect(status().isAccepted())
                .andExpect(header().string("Content-Location", statusUrl));

        Assert.assertEquals(job.getStatus(), JobStatus.SUBMITTED);
        Assert.assertEquals(job.getStatusMessage(), INITIAL_JOB_STATUS_MESSAGE);
        Assert.assertEquals(job.getProgress(), Integer.valueOf(0));
        Assert.assertEquals(job.getRequestURL(), "http://localhost" + API_PREFIX  + PATIENT_EXPORT_PATH);
        Assert.assertEquals(job.getResourceTypes(), null);
        Assert.assertEquals(job.getUser(), null);
    }

    @Test
    public void testPatientExportWithParameters() throws Exception {
        final String typeParams = "?_type=ExplanationOfBenefits&_outputFormat=application/fhir+ndjson&since=20191015";
        ResultActions resultActions = this.mockMvc.perform(get(API_PREFIX + "/" + PATIENT_EXPORT_PATH + typeParams)
                .contentType(MediaType.APPLICATION_JSON)).andDo(print());
        Job job = jobRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).iterator().next();

        String statusUrl = "http://localhost" + API_PREFIX + "/Job/" + job.getJobID() + "/$status";

        resultActions.andExpect(status().isAccepted())
                .andExpect(header().string("Content-Location", statusUrl));

        Assert.assertEquals(job.getStatus(), JobStatus.SUBMITTED);
        Assert.assertEquals(job.getStatusMessage(), INITIAL_JOB_STATUS_MESSAGE);
        Assert.assertEquals(job.getProgress(), Integer.valueOf(0));
        Assert.assertEquals(job.getRequestURL(), "http://localhost" + API_PREFIX + PATIENT_EXPORT_PATH + typeParams);
        Assert.assertEquals(job.getResourceTypes(), "ExplanationOfBenefits");
        Assert.assertEquals(job.getUser(), null);
    }

    @Test
    public void testPatientExportWithInvalidType() throws Exception {
        final String typeParams = "?_type=PatientInvalid,ExplanationOfBenefits";
        this.mockMvc.perform(get(API_PREFIX + "/" + PATIENT_EXPORT_PATH + typeParams)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.resourceType", Is.is("OperationOutcome")))
                .andExpect(jsonPath("$.issue[0].severity", Is.is("error")))
                .andExpect(jsonPath("$.issue[0].code", Is.is("invalid")))
                .andExpect(jsonPath("$.issue[0].details.text", Is.is("IllegalArgumentException: _type must be ExplanationOfBenefits")));
    }

    @Test
    public void testPatientExportWithInvalidOutputFormat() throws Exception {
        final String typeParams = "?_outputFormat=Invalid";
        this.mockMvc.perform(get(API_PREFIX + "/" + PATIENT_EXPORT_PATH + typeParams)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(400))
                .andExpect(jsonPath("$.resourceType", Is.is("OperationOutcome")))
                .andExpect(jsonPath("$.issue[0].severity", Is.is("error")))
                .andExpect(jsonPath("$.issue[0].code", Is.is("invalid")))
                .andExpect(jsonPath("$.issue[0].details.text", Is.is("IllegalArgumentException: An _outputFormat of Invalid is not valid")));
    }
}