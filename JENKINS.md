# Jenkins Pipeline Setup

## Multibranch Pipeline

Create new multibranch pipeline pointed at current project with the expected GitHub URL.

## Space Requirements

One of the integration testing tools we use, called Testcontainers, requires that the 
root volume `/` have at least 2GB of free space. If this is not the case mount an additional
volume to give it at least that much space.

## Credentials

Add the following credentials to the global credentials store

| Type | Name | Description
| ----- | ----- | ------ |
| Text | SANDBOX_BFD_KEYSTORE_PASSWORD | password to keystore
| File | SANDBOX_BFD_KEYSTORE | keystore file
| Text | HICN_HASH_PEPPER | 
| Text | CC_TEST_REPORTER_ID | code climate test reporter id for running code climate checks
| Text | OKTA_CLIENT_ID | 
| Text | OKTA_CLIENT_PASSWORD | 
| Text | SECONDARY_USER_OKTA_CLIENT_ID | 
| Text | SECONDARY_USER_OKTA_CLIENT_PASSWORD | 
| Text | HPMS_AUTH_KEY_ID | use the dev/impl/sandbox one
| Text | HPMS_AUTH_KEY_SECRET | use the dev/impl/sandbox one


## Tools Configuration

Install Java 13 and Maven 3.6.3 on the jenkins agent.

Go to the `<jenkins_ip>:8080/configureTools` url. Add a JDK and Maven installation
but do not allow Jenkins to install it automatically. For each installation set it to the home of the
installed Jenkins on the agent.

If you have multiple agents make sure the Java and Maven installations are to the same directory.

## Docker Upgrades

* Docker should be upgraded to the latest release.
* docker-compose must be upgraded to at least 1.27.0.rc2 or the latest release.

Like with Java and Maven, you should set the Docker installation at the following url: `<jenkins_ip>:8080/configureTools`.

## DNS Resolution Issues

Often when builds fail totally it is because of environmental issues with Jenkins. If it seems that the containers
cannot resolve any domains on the internet (Google, Facebook, CMS IDP, etc.) there are two potential fixes.

1) Restart docker on the Jenkins agents `systemctl restart docker`
2) Check that the domain `test.idp.idm.cms.gov` is available and name resolution works from the Jenkins agents. Sometimes
this IDP goes down and the tests rely on it.

