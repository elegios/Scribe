# Try client locally without a backend, but with live-reloading
```
cd client
lein figwheel
```
Then open localhost:3449

# Build client for use in appengine
```
cd client
lein clean
lein with-profile prod cljsbuild once
```

# Try local appengine
Have appengine in PATH, then (this is what it used to be, should be something starting with `gcloud app` now, not sure exactly what):
```
cd backend
goapp serve # To test that things appear correct
```

# Push to server
```
cd backend
gcloud app deploy
```
