output "instance_public_ip" {
  description = "Elastic IP of the memlayer EC2 instance"
  value       = aws_eip.memlayer.public_ip
}

output "ecr_repository_url" {
  description = "ECR repository URL for Docker images"
  value       = aws_ecr_repository.memlayer.repository_url
}

output "ssh_command" {
  description = "SSH command to connect to the instance"
  value       = "ssh ec2-user@${aws_eip.memlayer.public_ip}"
}

output "app_url" {
  description = "Application URL"
  value       = "https://api.${var.domain_name}"
}

output "nameservers" {
  description = "Nameservers to configure at your domain registrar"
  value       = aws_route53_zone.memlayer.name_servers
}
