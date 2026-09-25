# How search engines work

A search engine answers a query in three stages. First, a crawler or importer collects documents. Second, an indexer breaks every document into words, normalizes them, and builds an inverted index: a map from each word to the list of documents that contain it, together with the positions where it appears. Third, at query time the engine looks up each query word in the index, combines the posting lists, and ranks the matching documents.

Ranking decides which results appear first. Modern engines use BM25, which rewards documents that mention the query words often, gives more weight to rare words than to common ones, and normalizes for document length so that long pages do not win simply by being long. Words in the title usually count more than words in the body.

Positions make phrase search possible: the query "inverted index" only matches documents where the two words are adjacent and in that order. They also enable proximity ranking, which prefers documents where the query words appear close together.
