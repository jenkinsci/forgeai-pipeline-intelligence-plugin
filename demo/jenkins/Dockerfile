FROM jenkins/jenkins:2.580.2-lts-jdk21

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY casc.yaml /forgeai/casc.yaml
COPY init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/

ENV CASC_JENKINS_CONFIG=/forgeai/casc.yaml \
    JAVA_OPTS=-Djenkins.install.runSetupWizard=false
