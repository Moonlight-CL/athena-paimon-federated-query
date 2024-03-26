# Amazon Athena Paimon Connector

This connector enables Amazon Athena to communicate with Apache Paimon, making your Paimon table data accessible via Amazon Athena.

## Build & Package 
```shell
# Packaging Paimon Connector
mvn clean package -DskipTests

# Docker Cli Authentication and Create ECR Repo: athena-connector
aws ecr get-login-password --region us-west-2 | docker login --username AWS --password-stdin YourAccoutID.dkr.ecr.us-west-2.amazonaws.com
aws ecr create-repository --repository-name athena-connector --region us-west-2 --image-scanning-configuration scanOnPush=true --image-tag-mutability MUTABLE

# Tag the iamge and push it to ECR
docker build --platform linux/amd64 -t athena-paimon .
docker tag athena-paimon:latest YourAccoutID.dkr.ecr.us-west-2.amazonaws.com/athena-connector:latest
docker push YourAccoutID.dkr.ecr.us-west-2.amazonaws.com/athena-connector:latest
```
## samconfig.yaml
```yaml
version: 0.1
default:
  deploy:
    parameters:
      stack_name: 部署时的CloudFormation Stack名, 如:athena-paimon-connector
      region: us-west-2
      s3_bucket: 保存部署模版的S3 bucket
      s3_prefix: 保存部署模版的S3 路径
      confirm_changeset: true
      capabilities: CAPABILITY_IAM
      parameter_overrides:
        - AthenaCatalogName=在Athena中的Catalog名称, 也是Lambda的名称, 如:paimon
        - SpillBucket=Athena计算过程中Spill 数据的保存S3 bucket, 如: paimon-resc
        - SpillPrefix=Athena计算过程中Spill 数据的保存路径, 如: athena-spill
        - PaimonDataBucket=Paimon数据存储的S3 bucket, 如: paimon-resc
        - PaimonDataPrefix=Paimon数据存储的S3 路径,如: data/paimon/
        - AWSAccessKey=访问Paimon S3数据的Access Key
        - AWSSecretKey=访问Paimon S3数据的Secret Key
        - AWSSessionToken=可选,访问Paimon S3数据可以基于SessionToken来认证
        - LambdaTimeout=Lambda超时时间(秒), 如:900
        - LambdaMemory=Lambda的内存大小, 如: 3008
        - DisableSpillEncryption=是否禁用Spill数据加密, 如: true
```
## sam deploy
```shell
sam deploy -t athena-paimon.yaml --region us-west-2 \
--s3-bucket emr-resc --image-repository YourAccoutID.dkr.ecr.us-west-2.amazonaws.com/athena-connector \ 
--config-file ./samconfig.yaml
```

## sam delete
```shell
 sam delete --stack-name athena-paimon-connector --region us-west-2
```