output "vpc_id" { value = aws_vpc.main.id }
output "application_subnets" { value = [for subnet in aws_subnet.application : subnet.id] }
output "media_bucket" { value = aws_s3_bucket.media.id }
output "media_distribution_domain" { value = aws_cloudfront_distribution.media.domain_name }
output "media_signing_key_id" { value = aws_cloudfront_public_key.media.id }
output "database_address" { value = aws_db_instance.main.address }
output "database_secret_arn" { value = aws_db_instance.main.master_user_secret[0].secret_arn }
output "oidc_issuer" { value = "https://cognito-idp.${var.region}.amazonaws.com/${aws_cognito_user_pool.main.id}" }
output "oidc_client_id" { value = aws_cognito_user_pool_client.web.id }
output "container_repositories" { value = { for name, repo in aws_ecr_repository.application : name => repo.repository_url } }
output "ingest_queue_url" { value = aws_sqs_queue.media["media-ingest"].url }
output "result_queue_url" { value = aws_sqs_queue.media["media-results"].url }
output "application_secret_arns" { value = { for name, secret in aws_secretsmanager_secret.application : name => secret.arn } }
