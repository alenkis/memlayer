# Route53 hosted zone for memlayer.dev
resource "aws_route53_zone" "memlayer" {
  name = var.domain_name
}

# API — points to EC2 Elastic IP
resource "aws_route53_record" "api" {
  zone_id = aws_route53_zone.memlayer.id
  name    = "api.${var.domain_name}"
  type    = "A"
  ttl     = 300
  records = [aws_eip.memlayer.public_ip]
}

# Root domain — Vercel marketing site
resource "aws_route53_record" "root" {
  zone_id = aws_route53_zone.memlayer.id
  name    = var.domain_name
  type    = "A"
  ttl     = 300
  records = ["76.76.21.21"]
}

# www — redirect to root (Vercel)
resource "aws_route53_record" "www" {
  zone_id = aws_route53_zone.memlayer.id
  name    = "www.${var.domain_name}"
  type    = "CNAME"
  ttl     = 300
  records = ["cname.vercel-dns.com"]
}

# Dashboard (Vercel)
resource "aws_route53_record" "app" {
  zone_id = aws_route53_zone.memlayer.id
  name    = "app.${var.domain_name}"
  type    = "CNAME"
  ttl     = 300
  records = ["cname.vercel-dns.com"]
}

# Docs (Vercel)
resource "aws_route53_record" "docs" {
  zone_id = aws_route53_zone.memlayer.id
  name    = "docs.${var.domain_name}"
  type    = "CNAME"
  ttl     = 300
  records = ["cname.vercel-dns.com"]
}

# DMARC email policy
resource "aws_route53_record" "dmarc" {
  zone_id = aws_route53_zone.memlayer.id
  name    = "_dmarc.${var.domain_name}"
  type    = "TXT"
  ttl     = 300
  records = ["v=DMARC1; p=quarantine; adkim=r; aspf=r;"]
}
