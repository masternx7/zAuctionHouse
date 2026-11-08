group = "Hooks.DonutAuction"

dependencies {
    compileOnly(projects.api)
    compileOnly(fileTree("libs") { include("*.jar") })
}
