variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "us-east-1"
}

variable "instance_type" {
  description = "EC2 instance type"
  type        = string
  default     = "t3.small"
}

variable "ssh_public_key_path" {
  description = "Path to SSH public key file for EC2 access"
  type        = string
  default     = "~/.ssh/id_ed25519.pub"
}

variable "ebs_volume_size" {
  description = "Size of EBS data volume in GB"
  type        = number
  default     = 20
}

variable "openai_api_key" {
  description = "OpenAI API key (injected via op)"
  type        = string
  sensitive   = true
}

variable "groq_api_key" {
  description = "Groq API key (injected via op)"
  type        = string
  sensitive   = true
}

variable "domain_name" {
  description = "Root domain name for DNS records"
  type        = string
  default     = "memlayer.dev"
}

variable "allowed_ssh_cidrs" {
  description = "CIDR blocks allowed to SSH (empty = use SSM Session Manager instead)"
  type        = list(string)
  default     = []
}
