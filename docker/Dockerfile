FROM eclipse-temurin:21-jre-alpine

WORKDIR /solply

ENV TZ=Asia/Seoul

COPY ./build/libs/solply-server-0.0.1-SNAPSHOT.jar /solply/solply.jar

EXPOSE 8080

CMD ["java", "-Duser.timezone=Asia/Seoul", "-jar", "solply.jar"]