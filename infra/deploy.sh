#!/bin/bash
set -euo pipefail

# Usage: deploy.sh <ECR_URL> [IMAGE_TAG]
# Called by the deploy workflow via SSM.

ECR_URL="${1:?Usage: deploy.sh <ECR_URL> [IMAGE_TAG]}"
IMAGE_TAG="${2:-latest}"
REGION=us-east-1

# Login to ECR
aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "$ECR_URL"

# Pull specified image
docker pull "$ECR_URL:$IMAGE_TAG"

# Fetch secrets from SSM
OPENAI_KEY=$(aws ssm get-parameter --name /memlayer/openai-api-key \
  --with-decryption --region "$REGION" --query 'Parameter.Value' --output text)
GROQ_KEY=$(aws ssm get-parameter --name /memlayer/groq-api-key \
  --with-decryption --region "$REGION" --query 'Parameter.Value' --output text)

# Stop existing container
docker stop memlayer 2>/dev/null || true
docker rm memlayer 2>/dev/null || true

# Run new container
docker run -d \
  --name memlayer \
  --restart unless-stopped \
  --log-driver=awslogs \
  --log-opt awslogs-region="$REGION" \
  --log-opt awslogs-group=/memlayer/api \
  -p 8080:8080 \
  -v /data/memlayer:/data \
  -e OPENAI_API_KEY="$OPENAI_KEY" \
  -e GROQ_API_KEY="$GROQ_KEY" \
  -e DATAHIKE_BACKEND=file \
  -e DATAHIKE_PATH=/data/db \
  -e PROXIMUM_BACKEND=file \
  -e PROXIMUM_PATH=/data/vectors \
  -e MEMLAYER_PORT=8080 \
  -e DYNAMODB_TABLE=memlayer-rate-limits \
  -e AWS_REGION="$REGION" \
  "$ECR_URL:$IMAGE_TAG"

echo "memlayer deployed successfully (image: $IMAGE_TAG)"
