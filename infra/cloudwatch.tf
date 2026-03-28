# CloudWatch Log Group for memlayer API container logs
resource "aws_cloudwatch_log_group" "memlayer_api" {
  name              = "/memlayer/api"
  retention_in_days = 30

  tags = {
    Name = "memlayer-api-logs"
  }
}

# Alarm: error rate > 10 errors in 5 minutes
resource "aws_cloudwatch_log_metric_filter" "error_count" {
  name           = "memlayer-error-count"
  log_group_name = aws_cloudwatch_log_group.memlayer_api.name
  pattern        = "{ $.level = \"ERROR\" }"

  metric_transformation {
    name          = "ErrorCount"
    namespace     = "Memlayer"
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "high_error_rate" {
  alarm_name          = "memlayer-high-error-rate"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ErrorCount"
  namespace           = "Memlayer"
  period              = 300
  statistic           = "Sum"
  threshold           = 10
  alarm_description   = "More than 10 errors in 5 minutes"
  treat_missing_data  = "notBreaching"
}
