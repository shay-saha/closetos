resource "aws_secretsmanager_secret" "application" {
  for_each = {
    web-session   = "Server-side authentication session encryption"
    media-signing = "RSA-2048 PKCS#8 private key for signed wardrobe derivatives"
    database-app  = "Separate password for the closetos_app database runtime role"
  }
  name                    = "${local.name}/${each.key}"
  description             = each.value
  recovery_window_in_days = 30
  lifecycle { prevent_destroy = true }
}
