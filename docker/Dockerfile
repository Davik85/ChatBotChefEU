# syntax=docker/dockerfile:1
FROM gradle:8.7-jdk17 AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew clean installDist --no-daemon

FROM eclipse-temurin:17-jre AS runtime
ENV APP_HOME=/opt/chatbotchefeu
WORKDIR $APP_HOME
COPY --from=builder /workspace/build/install/ChatBotChefEU $APP_HOME
RUN useradd --system --home $APP_HOME chatbot
USER chatbot
EXPOSE 8080
ENTRYPOINT ["bin/ChatBotChefEU"]
