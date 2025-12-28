Please add a config option to set local-only mode to false for the current session. When the applicatuion has determined that it needs web search to answer a question and local-only mode is true, it should prompt the user whether to temporarily disable local-only mode for that session.
I.e instead of the following:

```
lca> Please investigate if there are ways we could recover from out of memory errors which sometimes happens when available ram memory is insufficient
Web search is disabled in local-only mode. Set assistant.local-only=false to enable.
```

It should say something like:

```
lca> Please investigate if there are ways we could recover from out of memory errors which sometimes happens when available ram memory is insufficient
Web search is disabled in local-only mode. Would you like to temporarily disable local-only mode for
this session? (y/n): y
Local-only mode disabled for this session, searching the web...
```

Also make it possible to set local-only mode in the configuration, i.e. instead of the current:

```
lca> /config
=== Configuration ===
Auto-paste: enabled                                                                                                                                                                                        
lca>
```

We should have:

```
lca> /config
=== Configuration ===
Auto-paste: enabled
Local-only: enabled  
lca>
```

To set it to false for the current session:

```
lca> /config local-only false
```