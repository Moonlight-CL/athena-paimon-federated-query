FROM public.ecr.aws/lambda/java:11

COPY target/athena-paimon-2024.01.1.jar ${LAMBDA_TASK_ROOT}/lib/

CMD ["me.aws.athena.connector.paimon.PaimonCompositeHandler::handleRequest"]