package se.alipsa.lca.tools

import spock.lang.Specification

class ContextPackerSpec extends Specification {

  def "packs and truncates context"() {
    given:
    def hits = (1..5).collect {
      new CodeSearchTool.SearchHit("file${it}.groovy", it, 1, "line ${it}")
    }
    ContextPacker packer = new ContextPacker()

    when:
    def packed = packer.pack(hits, 40)

    then:
    packed.truncated
    packed.included.size() < hits.size()
    packed.text
  }
}
