FROM openjdk:8-jre
WORKDIR /usr/src/equaliser-api
COPY build/libs/api-0.0.1-fat.jar build/libs/quasar-core-0.7.7-jdk8.jar src/main/conf/api.json ./
EXPOSE 80 443
CMD java -Dvertx.logger-delegate-factory-class-name=io.vertx.core.logging.SLF4JLogDelegateFactory -Dco.paralleluniverse.fibers.verifyInstrumentation -javaagent:$PWD/quasar-core-0.7.7-jdk8.jar -jar api-0.0.1-fat.jar -conf api.json
