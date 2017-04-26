FROM openjdk:8-jre
WORKDIR /opt/equaliser-api
COPY build/libs/api-1.0.0-fat.jar lib/quasar-core-0.7.7-jdk8.jar src/main/conf/api.json /opt/equaliser-api/
COPY countries /opt/equaliser-api/countries
COPY images /opt/equaliser-api/images
COPY lib/wait-for-it.sh /opt/equaliser-api/
EXPOSE 80
CMD java -Dvertx.logger-delegate-factory-class-name=io.vertx.core.logging.SLF4JLogDelegateFactory -Dco.paralleluniverse.fibers.verifyInstrumentation -javaagent:/opt/equaliser-api/quasar-core-0.7.7-jdk8.jar -jar api-1.0.0-fat.jar -conf api.json
