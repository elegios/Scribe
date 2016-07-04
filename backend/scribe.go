package scribe

import (
	"fmt"
	"net/http"
	"regexp"
	"log"
	"encoding/json"
	"strconv"
	"errors"

	"appengine"
	"appengine/user"
	"appengine/datastore"
)

var (
	pathRegex = regexp.MustCompile(`^/([^/]+)(?:/([^/]+))?$`)
)

func init() {
	http.HandleFunc("/", handler)
}

type env struct {
	w http.ResponseWriter
	r *http.Request
	ctx appengine.Context
}

type ProjectTop struct {
	Snapshots []int64
}

type Snapshot struct {
	Previous int64
	Top int64
	Nodes []int64
}

type Node struct {
	Name string
	Children []int64
	Collapsed bool
	Folder bool
	Text string `datastore:",noindex"`
	Notes string `datastore:",noindex"`
	Synopsis string `datastore:",noindex"`
}

/**
* Convention: return second value, bool, true iff we should just return
*             because the request has now been taken care of in its entirety
*/

func getUser(e env) (*datastore.Key, bool) {
	u := user.Current(e.ctx)
	if u == nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusUnauthorized)
		url, _ := user.LoginURL(e.ctx, "/")
		fmt.Fprintf(e.w, `You need to be logged in to access this page. <a href="%s">Sign in or register</a>`, url)
		return nil, true
	}
	return datastore.NewKey(e.ctx, "user", u.ID, 0, nil), false
}

func getLastSnapshot(e env, userKey *datastore.Key, projectId string) (*Snapshot, *datastore.Key, bool) {
	var projTop ProjectTop
	if err := datastore.Get(e.ctx, datastore.NewKey(e.ctx, "projectTop", projectId, 0, userKey), &projTop); err != nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusNotFound)
		fmt.Fprintf(e.w, `There is no project with this ID. <form action="/%s" method="post"><button type="submit">Create it</button></form>`, projectId)
		return nil, nil, true
	}

	var snapshot Snapshot
	snapshotKey := datastore.NewKey(e.ctx, "snapshot", "", projTop.Snapshots[len(projTop.Snapshots)-1], userKey)
	if err := datastore.Get(e.ctx, snapshotKey, &snapshot); err != nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(e.w, `Could not load the snapshot`)
		log.Println("Error loading snapshot", err)
		return nil, nil, true
	}

	return &snapshot, snapshotKey, false
}

func handler(w http.ResponseWriter, r *http.Request) {
	e := env {
		w: w,
		r: r,
		ctx: appengine.NewContext(r),
	}
	matches := pathRegex.FindStringSubmatch(r.URL.Path)
	if len(matches) > 0 {
		switch r.Method {
		case "GET":
			if getHandler(e, matches) {
				return
			}
		case "POST":
			if postHandler(e, matches) {
				return
			}
		}
	}

  if r.Method == "GET" && r.URL.Path == "/" {
    rootHandler(e);
    return
  }

	http.NotFound(w, r);
}

func rootHandler(e env) {
	userKey, done := getUser(e)
	if done {
		return
	}

	q := datastore.NewQuery("projectTop").Ancestor(userKey).KeysOnly()

	e.w.Header().Set("Content-type", "text/html; charset=utf-8")
	fmt.Fprintf(e.w, `<ul>`)
	for t := q.Run(e.ctx); ; {
		key, err := t.Next(nil)
		if err == datastore.Done {
			break
		}
		if err != nil {
			e.w.WriteHeader(http.StatusInternalServerError)
			fmt.Fprintf(e.w, `</ul>Got an error retrieving projects`)
			log.Println("Error retrieving projects", err)
			return
		}

		fmt.Fprintf(e.w, `<li><a href="/%s">%s</a></li>`, key.StringID(), key.StringID())
	}
	fmt.Fprintf(e.w, `</ul>`)

	url, _ := user.LogoutURL(e.ctx, "/")
	fmt.Fprintf(e.w, `<a href="%s">Sign out</a>`, url)
}

func getHandler(e env, matches []string) bool {
	switch matches[2] {
	case "":
		return projectTop(e, matches[1])

	case "document":
		return getDocument(e, matches[1])
	}

	return false
}

func projectTop(e env, projectId string) bool {
	e.w.Header().Set("Content-type", "text/html; charset=utf-8")
	userKey, done := getUser(e)
	if done {
		return true
	}

	snapshot, snapshotKey, done := getLastSnapshot(e, userKey, projectId)
	if done {
		return true
	}

	nodes := make([]Node, len(snapshot.Nodes))
	nodeKeys := make([]*datastore.Key, 0, len(snapshot.Nodes))
	for _, k := range snapshot.Nodes {
		nodeKeys = append(nodeKeys, datastore.NewKey(e.ctx, "node", "", k, snapshotKey))
	}
	if err := datastore.GetMulti(e.ctx, nodeKeys, nodes); err != nil {
		e.w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(e.w, `Could not load all nodes in the snapshot`)
		log.Println("Error loading nodes in snapshot", err)
		return true
	}

	treeToSend := make(map[string]interface{})
	for i, node := range nodes {
		id := strconv.FormatInt(nodeKeys[i].IntID(), 10)
		val := map[string]interface{}{
			"name": node.Name,
		}
		if node.Folder {
			val["collapsed"] = node.Collapsed
			if node.Children != nil {
				val["children"] = node.Children
			} else {
				val["children"] = make([]int64, 0, 0)
			}
		}
		treeToSend[id] = val
	}
	treeToSend["root"] = snapshot.Top

	fmt.Fprintf(e.w, `<html><head><link rel="stylesheet" type="text/css" href="static/site.css" /></head><body>`)
	fmt.Fprintf(e.w, `<script type="text/javascript">var StartingContent=null;function countWords(t){var c=0;t.replace(/\S+/g,function(){c++});return c};var StartingTree=`)
	if err := json.NewEncoder(e.w).Encode(treeToSend); err != nil {
		e.w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(e.w, "Could not generate json from the tree")
		log.Println("Error generating json from tree", err)
		return true
	}
	fmt.Fprintf(e.w, `</script><div id="app"></div><script type="text/javascript" src="static/client.js"></script>`)
	fmt.Fprintf(e.w, `<script type="text/javascript">window.onload=function(){scribe.core.run()}</script>`)
	fmt.Fprintf(e.w, `</body></html>`)
	return true;
}

func getDocument(e env, projectId string) bool {
	userKey, done := getUser(e)
	if done {
		return true
	}

	_, snapshotKey, done := getLastSnapshot(e, userKey, projectId) // FIXME: Unnecessary Get of snapshot
	var node Node
	id, err := strconv.ParseInt(e.r.FormValue("id"), 10, 64)
	if err != nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusBadRequest)
		fmt.Fprintf(e.w, "Document ids must be numeric")
		log.Println("Could not parse int id", err)
		return true
	}
	if err := datastore.Get(e.ctx, datastore.NewKey(e.ctx, "node", "", id, snapshotKey), &node); err != nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusNotFound)
		fmt.Fprintf(e.w, `Could not find the document`)
		log.Println("Error loading node", err)
		return true
	}

	treeToSend := map[string]string {
		"notes": node.Notes,
		"synopsis": node.Synopsis,
	}
	if !node.Folder {
		treeToSend["text"] = node.Text
	}

	if err := json.NewEncoder(e.w).Encode(treeToSend); err != nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(e.w, `Could not generate json from document`)
		log.Println("Error generating json from node", err)
		return true
	}

	e.w.Header().Set("Content-Type", "application/json; charset=UTF-8")
	return true
}

func postHandler(e env, matches []string) bool {
	switch matches[2] {
	case "":
		return createProject(e, matches[1])

	case "document":
		return createDocument(e, matches[1])

	case "tree":
		return updateTree(e, matches[1])

	case "documents":
		return updateDocuments(e, matches[1])
	}

	return false
}

func createProject(e env, projectId string) bool {
	userKey, done := getUser(e)
	if done {
		return true
	}

	var projTop ProjectTop
	topKey := datastore.NewKey(e.ctx, "projectTop", projectId, 0, userKey)
	if err := datastore.Get(e.ctx, topKey, &projTop); err == nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusConflict)
		fmt.Fprintf(e.w, `There is already a project with that id.`)
		return true;
	}

	err := datastore.RunInTransaction(e.ctx, func(ctx appengine.Context) error {
		snapshot := Snapshot {
			Previous: 0,
			Top: 0,
			Nodes: nil,
		}
		snapshotKey, err := datastore.Put(ctx, datastore.NewIncompleteKey(ctx, "snapshot", userKey), &snapshot)
		if err != nil {
			return err
		}

		rootNode := Node {
			Name: projectId,
			Children: nil,
			Collapsed: false,
			Folder: true,
			Text: "",
			Notes: "",
			Synopsis: "",
		}
		rootKey, err := datastore.Put(ctx, datastore.NewIncompleteKey(ctx, "node", snapshotKey), &rootNode)
		if err != nil {
			return err
		}

		snapshot.Top = rootKey.IntID()
		snapshot.Nodes = make([]int64, 1)
		snapshot.Nodes[0] = rootKey.IntID()

		_, err = datastore.Put(ctx, snapshotKey, &snapshot)
		if err != nil {
			return err
		}

		projTop.Snapshots = make([]int64, 1)
		projTop.Snapshots[0] = snapshotKey.IntID()

		_, err = datastore.Put(ctx, topKey, &projTop)
		return err
	}, nil)

	if err != nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(e.w, "Could not create project")
		log.Println("Error creating project:", err)
		return true
	}

	http.Redirect(e.w, e.r, fmt.Sprintf("/%s", projectId), http.StatusSeeOther)
	return true;
}

func createDocument(e env, projectId string) bool {
	userKey, done := getUser(e)
	if done {
		return true
	}

	snapshot, snapshotKey, done := getLastSnapshot(e, userKey, projectId)
	if done {
		return true
	}

	var nodeKey *datastore.Key

	err := datastore.RunInTransaction(e.ctx, func(ctx appengine.Context) error {
		node := Node {
			Folder: e.r.FormValue("folder") == "true",
		}
		var err error

		nodeKey, err = datastore.Put(ctx, datastore.NewIncompleteKey(ctx, "node", snapshotKey), &node)
		if err != nil {
			return err
		}

		parentId, err := strconv.ParseInt(e.r.FormValue("parent"), 10, 64)
		if err != nil {
			return err
		}
		var parent Node
		parentKey := datastore.NewKey(ctx, "node", "", parentId, snapshotKey)
		if err = datastore.Get(ctx, parentKey, &parent); err != nil {
			return err
		}
		if !parent.Folder {
			return errors.New("Parent is not a folder")
		}

		parent.Children = append(parent.Children, nodeKey.IntID())
		if _, err = datastore.Put(ctx, parentKey, &parent); err != nil {
			return err
		}

		snapshot.Nodes = append(snapshot.Nodes, nodeKey.IntID())
		_, err = datastore.Put(ctx, snapshotKey, snapshot)
		return err
	}, nil)

	if err != nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(e.w, "Could not create document")
		log.Println("Could not create document.", err)
		return true
	}

	if err = json.NewEncoder(e.w).Encode(map[string]int64{"id": nodeKey.IntID()}); err != nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(e.w, "Nigh impossible error")
		log.Println("Could not make json out of id")
	}

	e.w.Header().Set("Content-Type", "application/json; charset=UTF-8")
	return true
}

func updateTree(e env, projectId string) bool {
	userKey, done := getUser(e)
	if done {
		return true
	}

	snapshot, snapshotKey, done := getLastSnapshot(e, userKey, projectId)
	if done {
		return true
	}

	var treeToUpdate map[string]interface{}
	if err := json.NewDecoder(e.r.Body).Decode(&treeToUpdate); err != nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(e.w, `Could not decode the request`)
		return true
	}

	err := datastore.RunInTransaction(e.ctx, func(ctx appengine.Context) error {
		updatedSnapshot := false

		for k, node := range treeToUpdate {
			if k == "root" {
				snapshot.Top = int64(node.(float64))
				continue
			}

			id, err := strconv.ParseInt(k, 10, 64)
			if err != nil {
				return err
			}
			nodeKey := datastore.NewKey(ctx, "node", "", id, snapshotKey)
			if node == nil {
				// delete node, assume the rest of the tree is well-formed
				// FIXME: fetch node, check that children is empty, or delete recursively
				// may be deleted earlier, so we don't really care if the deletion fails
				_ = datastore.Delete(ctx, nodeKey)
				for i, node := range snapshot.Nodes {
					if node == id {
						snapshot.Nodes = append(snapshot.Nodes[:i], snapshot.Nodes[i+1:]...)
						updatedSnapshot = true
						break
					}
				}
				continue
			}

			// update a node, so get it, then change it, then put it
			var nodeToUpdate Node
			if err := datastore.Get(ctx, nodeKey, &nodeToUpdate); err != nil {
				return err
			}
			for k, v := range node.(map[string]interface{}) {
				switch k {
				case "name":
					nodeToUpdate.Name = v.(string)
				case "collapsed":
					nodeToUpdate.Collapsed = v.(bool)
				case "children":
					tempList := make([]int64, 0, len(v.([]interface{})))
					for _, v := range v.([]interface{}) {
						tempList = append(tempList, int64(v.(float64)))
					}
					nodeToUpdate.Children = tempList
				default:
					return errors.New("Unknown node property")
				}
			}
			if _, err := datastore.Put(ctx, nodeKey, &nodeToUpdate); err != nil {
				return err
			}
		}

		if updatedSnapshot {
			_, err := datastore.Put(ctx, snapshotKey, snapshot)
			return err
		}
		return nil
	}, nil)

	if err != nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(e.w, "Could not update the tree")
		log.Println("Error updating tree", err)
		return true
	}

	return true
}

func updateDocuments(e env, projectId string) bool {
	userKey, done := getUser(e)
	if done {
		return true
	}

	_, snapshotKey, done := getLastSnapshot(e, userKey, projectId) // FIXME: Unnecessary Get of snapshot
	if done {
		return true
	}

	var treeToUpdate map[string]map[string]string
	if err := json.NewDecoder(e.r.Body).Decode(&treeToUpdate); err != nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusBadRequest)
		fmt.Fprintf(e.w, `Could not decode the request`)
		return true
	}

	err := datastore.RunInTransaction(e.ctx, func(ctx appengine.Context) error {
		for stringId, node := range treeToUpdate {
			id, err := strconv.ParseInt(stringId, 10, 64)
			if err != nil {
				return err
			}

			nodeKey := datastore.NewKey(ctx, "node", "", id, snapshotKey)
			if node == nil {
				// will be / have been deleted by updateTree, so don't care here
				continue
			}

			var nodeToUpdate Node
			if err := datastore.Get(ctx, nodeKey, &nodeToUpdate); err != nil {
				return err
			}

			for k, v := range node {
				switch k {
					case "text":
						if !nodeToUpdate.Folder {
							nodeToUpdate.Text = v
						} else {
							return errors.New("A folder cannot have a text property")
						}
					case "notes":
						nodeToUpdate.Notes = v
					case "synopsis":
						nodeToUpdate.Synopsis = v
					default:
						return errors.New("Unknown property")
				}
			}

			if _, err := datastore.Put(ctx, nodeKey, &nodeToUpdate); err != nil {
				return err
			}
		}

		return nil
	}, nil)

	if err != nil {
		e.w.Header().Set("Content-type", "text/html; charset=utf-8")
		e.w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(e.w, "Could not update documents")
		log.Println("Error updating documents", err)
		return true
	}

	return true
}
