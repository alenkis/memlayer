resource "aws_ssm_parameter" "openai_api_key" {
  name        = "/memlayer/openai-api-key"
  description = "OpenAI API key for memlayer"
  type        = "SecureString"
  value       = var.openai_api_key

  tags = {
    Name = "memlayer-openai-key"
  }
}

resource "aws_ssm_parameter" "groq_api_key" {
  name        = "/memlayer/groq-api-key"
  description = "Groq API key for memlayer"
  type        = "SecureString"
  value       = var.groq_api_key

  tags = {
    Name = "memlayer-groq-key"
  }
}
