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
	reThing = regexp.MustCompile(`^/([^/]+)(?:/([^/]+))?$`)
)

func init() {
	http.HandleFunc("/", handler)
}

func handler(w http.ResponseWriter, r *http.Request) {
	matches := reThing.FindStringSubmatch(r.URL.Path)
	if len(matches) > 0 {
		switch r.Method {
			case "GET":
				if getHandler(matches, w, r) {
					return
				}
			case "POST":
				if postHandler(matches, w, r) {
					return
				}
		}
	}
	http.NotFound(w, r);
}

func getHandler(matches []string, w http.ResponseWriter, r *http.Request) bool {
	switch matches[2] {
	case "":
		return projectTop(matches[1], w, r)

	case "document":
		return getDocument(matches[1], w, r);
	}

	return false;
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

func projectTop(projectId string, w http.ResponseWriter, r *http.Request) bool {
	w.Header().Set("Content-type", "text/html; charset=utf-8")
	ctx := appengine.NewContext(r)
	u := user.Current(ctx)
	if u == nil {
		url, _ := user.LoginURL(ctx, fmt.Sprintf("/%s", projectId))
		fmt.Fprintf(w, `You need to be logged in to view this page. <a href="%s">Sign in or register</a>`, url)
		return true
	}
	var projTop ProjectTop
	userKey := datastore.NewKey(ctx, "user", u.ID, 0, nil)
	if err := datastore.Get(ctx, datastore.NewKey(ctx, "projectTop", projectId, 0, userKey), &projTop); err != nil {
		fmt.Fprintf(w, `There is no project with this ID. <form action="/%s" method="post"><button type="submit">Create it</button></form>`, projectId)
		return true;
	}

	var snapshot Snapshot
	snapshotKey := datastore.NewKey(ctx, "snapshot", "", projTop.Snapshots[len(projTop.Snapshots)-1], userKey)
	if err := datastore.Get(ctx, snapshotKey, &snapshot); err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(w, `Could not load the snapshot`)
		log.Println("Error loading snapshot", err)
		return true;
	}

	nodes := make([]Node, len(snapshot.Nodes))
	nodeKeys := make([]*datastore.Key, 0, len(snapshot.Nodes))
	for _, k := range snapshot.Nodes {
		nodeKeys = append(nodeKeys, datastore.NewKey(ctx, "node", "", k, snapshotKey))
	}
	if err := datastore.GetMulti(ctx, nodeKeys, nodes); err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(w, `Could not load all nodes in the snapshot`)
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

	fmt.Fprintf(w, `<html><head></head><body>`)
	fmt.Fprintf(w, `<script type="text/javascript">var StartingTree=`)
	if err := json.NewEncoder(w).Encode(treeToSend); err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(w, "Could not generate json from the tree")
		log.Println("Error generating json from tree", err)
		return true
	}
	fmt.Fprintf(w, `</script><div id="app"></div><script type="text/javascript" src="static/standalone_client.js"></script></body></html>`)
	return true;
}

func getDocument(projectId string, w http.ResponseWriter, r *http.Request) bool {
	ctx := appengine.NewContext(r)
	u := user.Current(ctx)
	if u == nil {
		w.WriteHeader(http.StatusUnauthorized)
		fmt.Fprintf(w, "You must be logged in to fetch a document.")
		return true
	}

	var projTop ProjectTop
	userKey := datastore.NewKey(ctx, "user", u.ID, 0, nil)
	if err := datastore.Get(ctx, datastore.NewKey(ctx, "projectTop", projectId, 0, userKey), &projTop); err != nil {
		fmt.Fprintf(w, `There is no project with this ID`)
		log.Println("Could not fetch project", err)
		return true;
	}

	var snapshot Snapshot
	snapshotKey := datastore.NewKey(ctx, "snapshot", "", projTop.Snapshots[len(projTop.Snapshots)-1], userKey)
	if err := datastore.Get(ctx, snapshotKey, &snapshot); err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(w, `Could not load the snapshot`)
		log.Println("Error loading snapshot", err)
		return true;
	}

	var node Node
	id, err := strconv.ParseInt(r.FormValue("id"), 10, 64)
	if err != nil {
		w.WriteHeader(http.StatusBadRequest)
		fmt.Fprintf(w, "Document ids must be numeric")
		log.Println("Could not parse int id", err)
	}
	if err := datastore.Get(ctx, datastore.NewKey(ctx, "node", "", id, snapshotKey), &node); err != nil {
		w.WriteHeader(http.StatusNotFound)
		fmt.Fprintf(w, `Could not find the document`)
		log.Println("Error loading node", err)
		return true
	}

	if node.Folder {
		w.WriteHeader(http.StatusBadRequest)
		fmt.Fprintf(w, `The given ID represents a folder`)
		return true
	}

	treeToSend := map[string]interface{}{
		"text": node.Text,
		"notes": node.Notes,
		"synopsis": node.Synopsis,
	}
	if err := json.NewEncoder(w).Encode(treeToSend); err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(w, `Could not generate json from document`)
		log.Println("Error generating json from node", err)
		return true
	}

	w.Header().Set("Content-Type", "application/json; charset=UTF-8")
	return true;
}

func postHandler(matches []string, w http.ResponseWriter, r *http.Request) bool {
	log.Println("Post matches:", matches)
	switch matches[2] {
	case "":
		return createProject(matches[1], w, r)

	case "document":
		return createDocument(matches[1], w, r)
	}

	return false;
}

func createProject(projectId string, w http.ResponseWriter, r *http.Request) bool {
	ctx := appengine.NewContext(r)
	u := user.Current(ctx)
	if u == nil {
		w.WriteHeader(http.StatusUnauthorized)
		fmt.Fprintf(w, "You must be logged in to create a project.")
		return true
	}

	var projTop ProjectTop
	userKey := datastore.NewKey(ctx, "user", u.ID, 0, nil)
	topKey := datastore.NewKey(ctx, "projectTop", projectId, 0, userKey)
	if err := datastore.Get(ctx, topKey, &projTop); err == nil {
		w.WriteHeader(http.StatusConflict)
		fmt.Fprintf(w, `There is already a project with that id.`)
		return true;
	}

	err := datastore.RunInTransaction(ctx, func(ctx appengine.Context) error {
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
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(w, "Could not create project")
		log.Println("Error creating project:", err)
		return true
	}

	http.Redirect(w, r, fmt.Sprintf("/%s", projectId), http.StatusSeeOther)
	return true;
}

func createDocument(projectId string, w http.ResponseWriter, r *http.Request) bool {
	ctx := appengine.NewContext(r)
	u := user.Current(ctx)
	if u == nil {
		w.WriteHeader(http.StatusUnauthorized)
		fmt.Fprintf(w, "You must be logged in to create a document.")
		return true
	}

	var projTop ProjectTop
	userKey := datastore.NewKey(ctx, "user", u.ID, 0, nil)
	if err := datastore.Get(ctx, datastore.NewKey(ctx, "projectTop", projectId, 0, userKey), &projTop); err != nil {
		w.WriteHeader(http.StatusNotFound)
		fmt.Fprintf(w, `There is no project with this ID`)
		log.Println("Could not fetch project", err)
		return true
	}

	var snapshot Snapshot
	snapshotKey := datastore.NewKey(ctx, "snapshot", "", projTop.Snapshots[len(projTop.Snapshots)-1], userKey)
	if err := datastore.Get(ctx, snapshotKey, &snapshot); err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(w, `Could not load the snapshot`)
		log.Println("Error loading snapshot", err)
		return true
	}

	var nodeKey *datastore.Key

	err := datastore.RunInTransaction(ctx, func(ctx appengine.Context) error {
		node := Node {
			Folder: r.FormValue("folder") == "true",
		}
		var err error

		nodeKey, err = datastore.Put(ctx, datastore.NewIncompleteKey(ctx, "node", snapshotKey), &node)
		if err != nil {
			return err
		}

		parentId, err := strconv.ParseInt(r.FormValue("parent"), 10, 64)
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
		_, err = datastore.Put(ctx, snapshotKey, &snapshot)
		return err
	}, nil)

	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprintf(w, "Could not create document")
		return true
	}

	if err = json.NewEncoder(w).Encode(map[string]interface{}{"id": nodeKey.IntID()}); err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		log.Println("Could not make json out of id")
	}
	w.Header().Set("Content-Type", "application/json; charset=UTF-8")
	return true
}
