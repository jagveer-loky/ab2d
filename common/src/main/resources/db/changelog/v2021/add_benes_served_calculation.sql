--liquibase formatted sql
--  -------------------------------------------------------------------------------------------------------------------

--changeset wnyffenegger:add_benes_served_calculation failOnError:true

CREATE TABLE ab2d_statistics(
                                statistic_name VARCHAR UNIQUE NOT NULL,
                                statistic_value INT UNIQUE NOT NULL
);

INSERT INTO ab2d_statistics(statistic_name, statistic_value)
VALUES ('total_benes_served', 0);

-- Create function for calculating total benes served by the AB2D
CREATE OR REPLACE PROCEDURE find_benes_served()
    LANGUAGE plpgsql
AS
$$
DECLARE
    con_number VARCHAR(5);
    ubc BIGINT;
    last_run TIMESTAMP WITH TIME ZONE;
BEGIN

    -- Table to store unique benes in
    CREATE TEMP TABLE benes_served(
        beneficiary_id BIGINT PRIMARY KEY NOT NULL
    );

    -- For each contract that has ever run a job in prod
    -- find all unique benes ever queried
    FOR con_number IN (SELECT DISTINCT(contract_number)
                       FROM job INNER JOIN contract con ON con.id = job.contract_id
                       WHERE contract_number LIKE 'S%' OR contract_number LIKE 'E%'
                       LIMIT 1000
    )
        LOOP

            ubc := (SELECT COUNT(*) FROM benes_served);
            RAISE NOTICE 'Inserting benes for %', con_number;
            RAISE NOTICE 'Benes before %', ubc;

            -- Get most recent run pulling data for the contract
            last_run := (
                SELECT created_at
                FROM job
                         INNER JOIN contract con ON con.id = job.contract_id
                WHERE status = 'SUCCESSFUL' AND contract_number = con_number
                LIMIT 1
            );
            RAISE NOTICE 'Last run %', last_run;

            -- Use most recent job to determine what months of enrollment for the contract have been queried.
            -- Once those months are known, use the temp table to deduplicate the enrollment against other contracts
            INSERT INTO benes_served(beneficiary_id)
            SELECT DISTINCT(beneficiary_id)
            FROM coverage
            WHERE contract = con_number AND year IN (2020, 2021, 2022, 2023)
              AND bene_coverage_search_event_id IN (
                -- Find all search events with coverage (should be one per month)
                SELECT bene_coverage_search_event_id
                FROM (
                         SELECT coverage.year, coverage.month, coverage.bene_coverage_search_event_id
                         FROM coverage
                                  INNER JOIN bene_coverage_period bcp ON coverage.bene_coverage_period_id = bcp.id
                         WHERE bcp.status = 'SUCCESSFUL' AND coverage.contract = con_number
                           AND bcp.year IN (2020, 2021)
                         GROUP BY coverage.contract, coverage.year, coverage.month, coverage.bene_coverage_search_event_id
                         ORDER BY coverage.contract, coverage.year, coverage.month
                     ) coverage_meta
                WHERE make_timestamptz(coverage_meta.year, coverage_meta.month, 1, 0, 0, 0, 'America/New_York') < last_run
            )
            ON CONFLICT (beneficiary_id) DO NOTHING;

        END LOOP;

    ubc := (SELECT COUNT(*) FROM benes_served);

    DROP TABLE IF EXISTS benes_served;

    UPDATE ab2d_statistics
    SET statistic_value = ubc
    WHERE statistic_name = 'total_benes_served';
END
$$;
