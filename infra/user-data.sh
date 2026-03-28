#!/bin/bash
set -euo pipefail

# Install Docker and AWS CLI
dnf update -y
dnf install -y docker aws-cli jq
systemctl enable docker
systemctl start docker

# Wait for EBS data volume
while [ ! -b /dev/xvdf ] && [ ! -b /dev/nvme1n1 ]; do sleep 1; done

# Determine actual device name (Nitro instances remap xvdf to nvme1n1)
if [ -b /dev/xvdf ]; then
  DATA_DEV="/dev/xvdf"
else
  DATA_DEV="/dev/nvme1n1"
fi

# Format only if not already formatted
if ! blkid "$DATA_DEV"; then
  mkfs.ext4 "$DATA_DEV"
fi

# Mount EBS data volume
mkdir -p /data
mount "$DATA_DEV" /data
echo "$DATA_DEV /data ext4 defaults,nofail 0 2" >> /etc/fstab

# Create data directory (subdirs created by the app)
mkdir -p /data/memlayer
chown -R 1000:1000 /data/memlayer

# Install Caddy (reverse proxy with automatic TLS)
dnf install -y 'dnf-command(copr)'
dnf copr enable -y @caddy/caddy epel-9-x86_64
dnf install -y caddy

# Configure Caddy
cat > /etc/caddy/Caddyfile <<CADDYFILE
${api_domain} {
    reverse_proxy localhost:8080
}
CADDYFILE

systemctl enable caddy
systemctl start caddy

# Deploy script (called on each deploy via SSH/SSM)
# Usage: memlayer-deploy [IMAGE_TAG]
# IMAGE_TAG defaults to "latest" if not provided
cat > /usr/local/bin/memlayer-deploy <<'DEPLOY_SCRIPT'
#!/bin/bash
set -euo pipefail

REGION="${aws_region}"
ECR_URL="${ecr_url}"
DYNAMODB_TABLE="${dynamodb_table}"
IMAGE_TAG="$${1:-latest}"

# Login to ECR
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$ECR_URL"

# Pull specified image
docker pull "$ECR_URL:$IMAGE_TAG"

# Fetch secrets from SSM
OPENAI_KEY=$(aws ssm get-parameter --name /memlayer/openai-api-key --with-decryption --region "$REGION" --query 'Parameter.Value' --output text)
GROQ_KEY=$(aws ssm get-parameter --name /memlayer/groq-api-key --with-decryption --region "$REGION" --query 'Parameter.Value' --output text)

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
  -e DYNAMODB_TABLE="$DYNAMODB_TABLE" \
  -e AWS_REGION="$REGION" \
  "$ECR_URL:$IMAGE_TAG"

echo "memlayer deployed successfully (image: $IMAGE_TAG)"
DEPLOY_SCRIPT

chmod +x /usr/local/bin/memlayer-deploy

echo "User data setup complete"
