#!/usr/bin/env groovy

def checkVersion(String dependencyString) {
    if (!dependencyString || dependencyString.trim().isEmpty()) {
        throw new IllegalArgumentException("Dependency string cannot be empty")
    }
    
    def parts = dependencyString.trim().split(':')
    if (parts.length < 2 || parts.length > 3) {
        throw new IllegalArgumentException("Invalid dependency format. Expected: 'group:artifact:version' or 'group:artifact'")
    }
    
    def group = parts[0]
    def artifact = parts[1]
    def version = parts.length == 3 ? parts[2] : null
    
    def url = "https://repo1.maven.org/maven2/${group.replace('.', '/')}/${artifact}/maven-metadata.xml"
    
    try {
        def metadata = new URL(url).text
        def xml = new XmlSlurper().parseText(metadata)
        def latestVersion = xml.versioning.latest.text()
        
        if (version == null) {
            println "Latest version: $latestVersion"
        } else {
            def isLatest = version == latestVersion
            println "Current version: $version, Latest version: $latestVersion, Is latest: $isLatest"
        }
    } catch (Exception e) {
        println "Error checking version for $dependencyString: ${e.message}"
    }
}

def args = System.args
if (args.length == 0) {
    println "Hey there! Usage: groovy checkVersion.groovy 'group:artifact:version' or 'group:artifact'"
    System.exit(1)
}

checkVersion(args[0])
