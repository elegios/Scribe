GET /<proj-id>/document?id=<id>
return:
{
  "text": <text>,
  "synopsis": <synopsis>,
  "notes": <notes>,
}

POST /<proj-id>/tree
{
  "root": <id>,
  <id>: {
    "name": <string>,
    "collapsed": <bool>,  // not present on leaves
    "children": [<id>],   // not present on leaves
  },
  <id>: null,             // means delete this entry
}
Only diffed things are present, i.e. never alter things that are not present
(notably collapsed which is a bool, its absence does not mean "collapsed": false)

POST /<proj-id>/document?parent=<id>&folder=<bool>
return:
{
  "id": <id>,
}
Creates a new document with the given node as a parent. "folder" is optional,
creates a folder if present and equal to true.

POST /<proj-id>/documents
{
  <id>: {
    "text": <text>,
    "notes": <notes>,
    "synopsis": <synopsis>,
  }
}
Only diffed things are present, do not alter fields that are not present.
(not even inner fields, i.e. "notes")

GET /<proj-id>
(cond
  (not logged-in)
  (login-link)

  (not project)
  (create-project-link)

  (project)
  (show project))
