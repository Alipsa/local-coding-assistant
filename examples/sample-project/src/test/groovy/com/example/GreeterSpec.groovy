package com.example

import spock.lang.Specification

// Not @CompileStatic: Spock rewrites feature methods at compile time, which static compilation rejects.
class GreeterSpec extends Specification {

  def "greet returns default when name is blank"() {
    given:
    Greeter greeter = new Greeter()

    expect:
    greeter.greet("  ") == "Hello, World!"
  }

  def "greet trims name"() {
    given:
    Greeter greeter = new Greeter()

    expect:
    greeter.greet(" Ada ") == "Hello, Ada!"
  }
}
