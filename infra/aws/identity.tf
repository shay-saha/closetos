resource "aws_cognito_user_pool" "main" {
  name                     = local.name
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]
  deletion_protection      = "ACTIVE"
  mfa_configuration        = "OPTIONAL"
  software_token_mfa_configuration {
    enabled = true
  }
  password_policy {
    minimum_length                   = 12
    require_lowercase                = true
    require_numbers                  = true
    require_symbols                  = true
    require_uppercase                = true
    temporary_password_validity_days = 3
  }
  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }
  user_attribute_update_settings {
    attributes_require_verification_before_update = ["email"]
  }
}
resource "aws_cognito_user_pool_client" "web" {
  name                                 = "${local.name}-web"
  user_pool_id                         = aws_cognito_user_pool.main.id
  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "profile", "email"]
  supported_identity_providers         = ["COGNITO"]
  callback_urls                        = ["${local.origin}/api/auth/callback/wardrobe"]
  logout_urls                          = ["${local.origin}/signin"]
  explicit_auth_flows                  = ["ALLOW_REFRESH_TOKEN_AUTH"]
  enable_token_revocation              = true
  prevent_user_existence_errors        = "ENABLED"
  access_token_validity                = 30
  id_token_validity                    = 30
  refresh_token_validity               = 7
  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }
}
resource "aws_cognito_user_pool_domain" "main" {
  domain       = "${local.name}-${local.account_id}"
  user_pool_id = aws_cognito_user_pool.main.id
}
resource "aws_cognito_user_group" "administrators" {
  name         = "closetos-admin"
  user_pool_id = aws_cognito_user_pool.main.id
  description  = "Accounts permitted to rebuild semantic search"
}
