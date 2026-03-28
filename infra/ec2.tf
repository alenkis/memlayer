# SSH key pair
resource "aws_key_pair" "memlayer" {
  key_name   = "memlayer-key"
  public_key = file(var.ssh_public_key_path)
}

# IAM role for EC2 (ECR pull + SSM read)
resource "aws_iam_role" "memlayer_ec2" {
  name = "memlayer-ec2-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "ec2.amazonaws.com"
      }
    }]
  })
}

resource "aws_iam_role_policy" "memlayer_ec2" {
  name = "memlayer-ec2-policy"
  role = aws_iam_role.memlayer_ec2.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ecr:GetAuthorizationToken"
        ]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage"
        ]
        Resource = aws_ecr_repository.memlayer.arn
      },
      {
        Effect = "Allow"
        Action = [
          "ssm:GetParameter",
          "ssm:GetParameters"
        ]
        Resource = [
          aws_ssm_parameter.openai_api_key.arn,
          aws_ssm_parameter.groq_api_key.arn
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "ssm:UpdateInstanceInformation",
          "ssmmessages:CreateControlChannel",
          "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel",
          "ssmmessages:OpenDataChannel"
        ]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem"
        ]
        Resource = aws_dynamodb_table.rate_limits.arn
      },
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents",
          "logs:DescribeLogStreams"
        ]
        Resource = "${aws_cloudwatch_log_group.memlayer_api.arn}:*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "memlayer_ec2" {
  name = "memlayer-ec2-profile"
  role = aws_iam_role.memlayer_ec2.name
}

# EC2 instance
resource "aws_instance" "memlayer" {
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_type
  key_name               = aws_key_pair.memlayer.key_name
  vpc_security_group_ids = [aws_security_group.memlayer.id]
  iam_instance_profile   = aws_iam_instance_profile.memlayer_ec2.name
  subnet_id              = data.aws_subnets.default.ids[0]

  user_data = templatefile("${path.module}/user-data.sh", {
    aws_region     = var.aws_region
    ecr_url        = aws_ecr_repository.memlayer.repository_url
    dynamodb_table = aws_dynamodb_table.rate_limits.name
    api_domain     = "api.${var.domain_name}"
  })

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 10
  }

  tags = {
    Name = "memlayer"
  }
}

# EBS data volume (persistent across stop/start)
resource "aws_ebs_volume" "memlayer_data" {
  availability_zone = data.aws_subnet.selected.availability_zone
  size              = var.ebs_volume_size
  type              = "gp3"

  tags = {
    Name = "memlayer-data"
  }
}

resource "aws_volume_attachment" "memlayer_data" {
  device_name  = "/dev/xvdf"
  volume_id    = aws_ebs_volume.memlayer_data.id
  instance_id  = aws_instance.memlayer.id
  force_detach = false
}

# Elastic IP
resource "aws_eip" "memlayer" {
  instance = aws_instance.memlayer.id
  domain   = "vpc"

  tags = {
    Name = "memlayer-eip"
  }
}
