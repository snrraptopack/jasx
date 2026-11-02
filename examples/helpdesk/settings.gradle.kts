// A support helpdesk on the web target and Spring Boot, laid out as a production application would be: a domain
// package with a repository and a service, controllers for pages, a REST API and file downloads, configuration
// properties, and page tests. The pages are server-driven, with plain JavaScript where the browser should answer
// at once (drag and drop, keyboard shortcuts, dialogs, a chart, relative times, drafts).
//
// Like the other examples, it takes JASX straight from this repository's source through a composite build, so
// nothing has to be published first.

pluginManagement {
    includeBuild("../..")
}

includeBuild("../..")

rootProject.name = "helpdesk"
