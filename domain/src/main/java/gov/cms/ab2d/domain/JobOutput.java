package gov.cms.ab2d.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

@Entity
@Getter
@Setter
public class JobOutput {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne
    private Job job;

    @Column(columnDefinition = "text")
    private String filePath;

    private String fhirResourceType;

}
