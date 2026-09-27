resource "aws_budgets_budget" "account_spend" {
  name         = "${local.name}-monthly-account-spend"
  account_id   = local.account_id
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_types {
    include_credit  = false
    include_refund  = false
    include_tax     = true
    include_support = true
    use_blended     = false
    use_amortized   = true
  }

  dynamic "notification" {
    for_each = {
      actual_warning = { type = "ACTUAL", threshold = 80 }
      actual_limit   = { type = "ACTUAL", threshold = 100 }
      forecast_limit = { type = "FORECASTED", threshold = 100 }
    }
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value.threshold
      threshold_type             = "PERCENTAGE"
      notification_type          = notification.value.type
      subscriber_email_addresses = var.budget_alert_emails
    }
  }
}
