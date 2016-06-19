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
Have appengine in PATH, then:
```
cd backend
goapp serve # To test that things appear correct
```

# Push to server
```
cd backend
appcfg.py -A e-scribe update .
```
