# Internal Test Environment Appendices

## Table of Contents

1. [Appendix A: Destroy complete environment](#appendix-a-destroy-complete-environment)
1. [Appendix B: Retest terraform using existing AMI](#appendix-b-retest-terraform-using-existing-ami)
1. [Appendix C: Stop and restart the ECS cluster](#appendix-c-stop-and-restart-the-ecs-cluster)
1. [Appendix D: Create an S3 bucket with AWS CLI](#appendix-d-create-an-s3-bucket-with-aws-cli)
1. [Appendix E: Verify EFS mounting on worker node](#appendix-e-verify-efs-mounting-on-worker-node)
1. [Appendix F: Verify PostgreSQL](#appendix-f-verify-postgresql)
1. [Appendix G: Note the product code for CentOS 7 AMI](#appendix-g-note-the-product-code-for-centos-7-ami)

## Appendix A: Destroy complete environment

1. Change to the environment directory

   ```ShellSession
   $ cd ~/code/ab2d/Deploy/terraform/environments/cms-ab2d-sbdemo
   ```

1. Destroy the environment

   *Example to destroy the complete environment:*
   
   ```ShellSession
   $ ./destroy-environment.sh --environment=sbdemo
   ```

   *Example to destroy the environment, but preserve the AMIs:*
   
   ```ShellSession
   $ ./destroy-environment.sh --environment=sbdemo --keep-ami
   ```

   *Example to destroy the environment, but preserve the networking:*
   
   ```ShellSession
   $ ./destroy-environment.sh --environment=sbdemo --keep-network
   ```

   *Example to destroy the environment, but preserve both the AMIs and the networking:*
   
   ```ShellSession
   $ ./destroy-environment.sh --environment=sbdemo --keep-ami --keep-network
   ```

## Appendix B: Retest terraform using existing AMI

1. If you haven't yet destroyed the existing API module, jump to the following section

   [Appendix A: Destroy complete environment](#appendix-a-destroy-complete-environment)
   
1. Set AWS profile

   ```ShellSession
   $ export AWS_PROFILE="sbdemo"
   ```

1. Delete existing log file

   ```ShelSession
   $ rm -f /var/log/terraform/tf.log
   ```

1. Change to the "Deploy" directory

   ```ShellSession
   $ cd ~/code/ab2d/Deploy
   ```

1. Deploy application components

   ```ShellSession
   $ ./deploy.sh --environment=sbdemo --auto-approve
   ```

## Appendix C: Stop and restart the ECS cluster

1. Connect to the deployment controller instance

   *Format:*

   ```ShellSession
   $ ssh centos@{public ip address of deployment controller}
   ```
   
   *Example:*
   
   ```ShellSession
   $ ssh centos@3.84.160.10
   ```

1. Connect to the first ECS instance from the deployment controller

   *Format:*
   
   ```ShellSession
   $ ssh centos@{private ip address of first ecs instance}
   ```
   
   *Example:*
   
   ```ShellSession
   $ ssh centos@10.124.4.246
   ```

1. Stop the ecs agent on the API ECS instances

   ```ShellSession
   $ ssh centos@10.124.4.246 'docker stop ecs-agent'
   $ ssh centos@10.124.5.116 'docker stop ecs-agent'
   ```

1. Stop the ecs agent on the worker ECS instances

   ```ShellSession
   $ ssh centos@10.124.4.163 'docker stop ecs-agent'
   $ ssh centos@10.124.5.89 'docker stop ecs-agent'
   ```

1. Start the ecs agent on the API ECS instances

   ```ShellSession
   $ ssh centos@10.124.4.246 'docker start ecs-agent'
   $ ssh centos@10.124.5.116 'docker start ecs-agent'
   ```

1. Start the ecs agent on the worker ECS instances

   ```ShellSession
   $ ssh centos@10.124.4.163 'docker start ecs-agent'
   $ ssh centos@10.124.5.89 'docker start ecs-agent'
   ```

## Appendix D: Create an S3 bucket with AWS CLI

1. Create S3 file bucket

   ```ShellSession
   $ aws s3api create-bucket --bucket cms-ab2d-cloudtrail-demo --region us-east-1
   ```

1. Note that the "Elastic Load Balancing Account ID for us-east-1" is the following:

   ```
   127311923021
   ```

1. Note that the "Elastic Load Balancing Account ID" for other regions can be found here

   > See https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/enable-access-logs.html
   
1. Block public access on bucket

   ```ShellSession
   $ aws s3api put-public-access-block \
     --bucket cms-ab2d-cloudtrail-demo \
     --region us-east-1 \
     --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
   ```

1. Give "Write objects" and "Read bucket permissions" to the "S3 log delivery group" of the "cms-ab2d-cloudtrail-demo" bucket

   ```ShellSession
   $ aws s3api put-bucket-acl \
     --bucket cms-ab2d-cloudtrail-demo \
     --grant-write URI=http://acs.amazonaws.com/groups/s3/LogDelivery \
     --grant-read-acp URI=http://acs.amazonaws.com/groups/s3/LogDelivery
   ```

1. Change to the s3 bucket policies directory

   ```ShellSession
   $ cd ~/code/ab2d/Deploy/aws/s3-bucket-policies
   ```
   
1. Add this bucket policy to the "cms-ab2d-cloudtrail-demo" S3 bucket

   ```ShellSession
   $ aws s3api put-bucket-policy \
     --bucket cms-ab2d-cloudtrail-demo \
     --policy file://cms-ab2d-cloudtrail-bucket-policy.json
   ```

## Appendix E: Verify EFS mounting on worker node

1. Set target profile

   *Example for the "semanticbitsdemo" AWS account:*
   
   ```ShellSession
   $ export AWS_PROFILE="sbdemo"
   ```

1. Get and note the file system id of EFS

   1. Enter the following
   
      ```ShellSession
      $ aws efs describe-file-systems | grep FileSystemId
      ```

   1. Note the output

      *Format:*
      
      ```
      "FileSystemId": "{efs file system id}",
      ```

      *Example:*
      
      ```
      "FileSystemId": "fs-2a03d9ab",
      ```

1. Connect to deployment controller

   *Format:*

   ```ShellSession
   $ ssh centos@{public ip of deployment controller}
   ```

   *Example:*
   
   ```ShellSession
   $ ssh centos@3.233.33.144
   ```
   
1. Connect to a worker node from the deployment controller

   *Format:*

   ```ShellSession
   $ ssh centos@{private ip of a worker node}
   ```
   
   *Example:*

   ```ShellSession
   $ ssh centos@10.124.4.104
   ```
   
1. Examine the file system table

   1. Enter the following
   
      ```ShellSession
      $ cat /etc/fstab
      ```

   1. Examine the EFS line in the output

      *Format:*
      
      ```
      {efs file system id} /mnt/efs efs _netdev,tls 0 0
      ```
      
      *Example:*
      
      ```
      fs-2a03d9ab /mnt/efs efs _netdev,tls 0 0
      ```

   1. Verify that the file system id matches the deployed EFS

## Appendix F: Verify PostgreSQL

1. Get database endpoint

   ```ShellSession
   $ aws rds describe-db-instances --db-instance-identifier cms-ab2d-sbdemo --query="DBInstances[0].Endpoint.Address"
   ```

1. Note the output (this is the psql host)

   *Example:*
   
   ```
   cms-ab2d-sbdemo.cr0bialx3sap.us-east-1.rds.amazonaws.com
   ```
   
1. Connect to the deployment controller instance

   *Format:*

   ```ShellSession
   $ ssh centos@{public ip address of deployment controller}
   ```
   
   *Example:*
   
   ```ShellSession
   $ ssh centos@52.206.57.78
   ```
   
1. Test connecting to database

   1. Enter the following
   
      *Format:*
   
      ```ShellSession
      $ psql --host {host} --username={database username} --dbname={database name}
      ```

   1. Enter database password when prompted

## Appendix G: Note the product code for CentOS 7 AMI

1. Note that the CentOS 7 AMI is used for testing in the internal test environment

1. Open Chrome

1. Enter the following in the address bar

   > https://wiki.centos.org/Cloud/AWS

1. Scroll down to the **Images** section

1. Note the product code for "CentOS-7 x86_64"

   ```
   aw0evgkw8e5c1q413zgy5pjce
   ```