package se.alipsa.lca.tools

import spock.lang.Specification

class SecretScannerSpec extends Specification {

  def "detects known secret patterns"() {
    given:
    SecretScanner scanner = new SecretScanner()
    String content = """
key=AKIA1234567890ABCDEF
aws_secret_access_key = abcdefghijklmnopqrstuvwxyz0123456789ABCD
token=ghp_1234567890abcdef1234567890abcdef1234
"""

    when:
    def findings = scanner.scan(content)

    then:
    findings.size() == 3
    findings.any { it.label == "AWS Access Key" }
    findings.any { it.label == "AWS Secret Key" }
    findings.any { it.label == "GitHub Token" }
  }
}
