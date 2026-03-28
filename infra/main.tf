terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    bucket         = "memlayer-terraform-state"
    key            = "memlayer/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "memlayer-terraform-locks"
    encrypt        = true
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "memlayer"
      ManagedBy = "terraform"
    }
  }
}

# Default VPC (pre-alpha simplicity)
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

# Amazon Linux 2023
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }

  filter {
    name   = "state"
    values = ["available"]
  }
}

data "aws_subnet" "selected" {
  id = data.aws_subnets.default.ids[0]
}

data "aws_caller_identity" "current" {}
