resource "aws_dynamodb_table" "rate_limits" {
  name         = "memlayer-rate-limits"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  tags = {
    Name = "memlayer-rate-limits"
  }
}
