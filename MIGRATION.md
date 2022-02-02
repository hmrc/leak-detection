# MongoDB Migration Scripts

## Back-fill leaks collection
_0.128.0 -> 0.129.0_

```javascript
db.getCollection('reports').aggregate([
{$group:{
  _id:{repoName: "$repoName", branch:"$branch"},
  data: {$first: '$$ROOT'}}
},
{$replaceRoot: {newRoot: "$data"}},
{$unwind: "$inspectionResults"},
{$project:{ 
    "repoName": 1, 
    "branch":1, 
    "timestamp":1, 
    "reportId": "$_id", 
    "ruleId": {$ifNull: ["$inspectionResults.ruleId" , "unknown"]}, 
    "description": "$inspectionResults.description", 
    "filePath": "$inspectionResults.filePath", 
    "scope": "$inspectionResults.scope", 
    "lineNumber": "$inspectionResults.lineNumber", 
    "urlToSource": "$inspectionResults.urlToSource", 
    "lineText": "$inspectionResults.lineText", 
    "matches": "$inspectionResults.matches", 
    "priority": {$ifNull: ["$inspectionResults.priority" , "low"]}}
},
{$project: {_id: 0}},
{$out: "leaks"}], {allowDiskUse: true})
```
Non-destructive, converts outstanding leaks from the `reports` collection into individual `leaks` records.

## Refactor reports collection
_0.134.0 -> 1.335.0_

```javascript
// migration, uses bulk-update as there's ~ 900k records
var bulk = db.reports.initializeUnorderedBulkOp();

db.getCollection('reports').find({}).forEach(r => {
    totalLeaks = 0;
    rules = {};
    if(r.inspectionResults != undefined) {
        totalLeaks += r.inspectionResults.length;
        r.inspectionResults.forEach(r => {
            if(rules[r.ruleId]==null) rules[r.ruleId]=0;
            rules[r.ruleId]++
        });
    }

    if(r.leakResolution != undefined) {
        totalLeaks += r.leakResolution.resolvedLeaks.length;
        r.leakResolution.resolvedLeaks.forEach(r => {
            if(rules[r.ruleId]==null) rules[r.ruleId]=0;
            rules[r.ruleId]++
        })
    }

    bulk.find({_id: r._id}).update({$set: {rulesViolated: rules, totalLeaks: totalLeaks, reportId: r._id}})
});

res = bulk.execute();
print(res)

// Cleanup
db.getCollection('reports').update({}, {$unset: {inspectionResults:1, leakResolution:1}}, {multi:true})
```
Destructive update, adds leak counts to the report record and removes the nested arrays of violations (these are now in the leaks collection).
Done as a js script rather than an aggregation due to the complexity of the conversions.
Takes ~60 secs to run, as it has to update every record.