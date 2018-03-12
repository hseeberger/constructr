# ConstructR #

[![Join the chat at https://gitter.im/hseeberger/constructr](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/hseeberger/constructr?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

ConstructR is for bootstrapping (construction) an [Akka](http://akka.io) cluster by using a coordination service.

Disambiguation: Despite the similar name, ConstructR is not related to [Lightbend ConductR](http://www.lightbend.com/products/conductr).

ConstructR utilizes a key-value coordination service like etcd to automate bootstrapping or joining a cluster. It stores each member node under the key `/constructr/$clusterName/nodes/$address` where `$clusterName` is for disambiguating multiple clusters and `$address` is a Base64 encoded Akka `Address`. These keys expire after a configurable time in order to avoid stale information. Therefore ConstructR refreshes each key periodically.

In a nutshell, ConstructR is a state machine which first tries to get the nodes from the coordination service. If none are available it tries to acquire a lock, e.g. via a CAS write for etcd, and uses itself or retries getting the nodes. Then it joins using these nodes as seed nodes. After that it adds its address to the nodes and starts the refresh loop:

```
                  ┌───────────────────┐              ┌───────────────────┐
              ┌──▶│   GettingNodes    │◀─────────────│BeforeGettingNodes │
              │   └───────────────────┘    delayed   └───────────────────┘
              │             │     │                            ▲
  join-failed │   non-empty │     └──────────────────────┐     │ failure
              │             ▼               empty        ▼     │
              │   ┌───────────────────┐              ┌───────────────────┐
              └───│      Joining      │◀─────────────│      Locking      │
                  └───────────────────┘    success   └───────────────────┘
                            │
              member-joined │
                            ▼
                  ┌───────────────────┐
                  │    AddingSelf     │
                  └───────────────────┘
                            │     ┌────────────────────────────┐
                            │     │                            │
                            ▼     ▼                            │
                  ┌───────────────────┐              ┌───────────────────┐
                  │ RefreshScheduled  │─────────────▶│    Refreshing     │
                  └───────────────────┘              └───────────────────┘
```

If something goes finally wrong when interacting with the coordination service, e.g. a permanent timeout after a configurable number of retries, ConstructR terminates its `ActorSystem` in the spirit of "fail fast".

``` scala
// All releases including intermediate ones are published here,
// final ones are also published to Maven Central.
resolvers += Resolver.bintrayRepo("hseeberger", "maven")

libraryDependencies ++= Vector(
  "de.heikoseeberger" %% "constructr" % "0.19.0",
  "de.heikoseeberger" %% "constructr-coordination-etcd" % "0.19.0", // in case of using etcd for coordination
  ...
)
```

Simply add the `ConstructrExtension` to the `extensions` configuration setting:

```
akka.extensions = [de.heikoseeberger.constructr.ConstructrExtension]
```

This will start the `Constructr` actor as a system actor. Alternatively start it yourself as early as possible if you feel so inclined.

The following listing shows the available configuration settings with their defaults:

```
constructr {
  coordination {
    host = localhost
    port = 2379
  }

  coordination-timeout    = 3 seconds  // Maximum response time for coordination service (e.g. etcd)
  join-timeout            = 15 seconds // Might depend on cluster size and network properties
  abort-on-join-timeout   = false      // Abort the attempt to join if true; otherwise restart the process from scratch
  max-nr-of-seed-nodes    = 0          // Any nonpositive value means Int.MaxValue
  nr-of-retries           = 2          // Nr. of tries are nr. of retries + 1
  refresh-interval        = 30 seconds // TTL is refresh-interval * ttl-factor
  retry-delay             = 3 seconds  // Give coordination service (e.g. etcd) some delay before retrying
  ttl-factor              = 2.0        // Must be greater or equal 1 + ((coordination-timeout * (1 + nr-of-retries) + retry-delay * nr-of-retries)/ refresh-interval)!
  ignore-refresh-failures = false      // Ignore failures once machine is already in "Refreshing" state. It prevents from FSM being terminated due to exhausted number of retries.

}
```

## Coordination

ConstructR comes with out-of-the-box support for etcd: simply depend on the "constructr-coordination-etcd" module. If you want to use some other coordination backend, e.g. Consul, simply implement the `Coordination` trait from the "constructr-coordination" module and make sure to provide the fully qualified class name via the `constructr.coordination.class-name` configuration setting.

### Community Coordination Implementations

There are some implementations for other coordination backends than etcd:

* [Tecsisa/constructr-consul](https://github.com/Tecsisa/constructr-consul): This library enables to use Consul as cluster coordinator in a ConstructR based cluster.
* [everpeace/constructr-redis](https://github.com/everpeace/constructr-redis): This library enables to use Redis as cluster coordinator in a ConstructR based cluster.
* [typesafehub/constructr-zookeeper](https://github.com/typesafehub/constructr-zookeeper): This library enables to use ZooKeeper as cluster coordinator in a ConstructR based cluster.

## Testing

etcd must be running, e.g.:

```
docker run \
  --detach \
  --name etcd \
  --publish 2379:2379 \
  quay.io/coreos/etcd:v2.3.8 \
  --listen-client-urls http://0.0.0.0:2379 \
  --advertise-client-urls http://192.168.99.100:2379
```

## Contribution policy ##

Contributions via GitHub pull requests are gladly accepted from their original author. Along with any pull requests, please state that the contribution is your original work and that you license the work to the project under the project's open source license. Whether or not you state this explicitly, by submitting any copyrighted material via pull request, email, or other means you agree to license the material under the project's open source license and warrant that you have the legal authority to do so.

Please make sure to follow these conventions:
- For each contribution there must be a ticket (GitHub issue) with a short descriptive name, e.g. "Respect seed-nodes configuration setting"
- Work should happen in a branch named "ISSUE-DESCRIPTION", e.g. "32-respect-seed-nodes"
- Before a PR can be merged, all commits must be squashed into one with its message made up from the ticket name and the ticket id, e.g. "Respect seed-nodes configuration setting (closes #32)"

## License ##

This code is open source software licensed under the [Apache 2.0 License](http://www.apache.org/licenses/LICENSE-2.0.html).
