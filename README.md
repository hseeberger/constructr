# ConstructR #

ConstructR utilizes [etcd](https://github.com/coreos/etcd) to automate creating or joining an [Akka](http://akka.io) cluster. It stores each member node under the key `/constructr/nodes/$address` where `$address` is a Base64 encoded Akka `Address`. These keys expire after a configurable time in order to avoid stale information. Therefore ConstructR refreshes each key periodically.

In a nutshell, ConstructR is a state machine which first tries to get the nodes from etcd. If none are available it tries to acquire a lock (CAS write) and uses itself or retries getting the nodes. Then it joins using these nodes as seed nodes. After that it adds its address to the nodes and starts the refresh loop:

```
     ┌───────────────────┐              ┌───────────────────┐
     │   GettingNodes    │◀─────────────│BeforeGettingNodes │
     └───────────────────┘    delayed   └───────────────────┘
               │     │                            ▲
     non-empty │     └──────────────────────┐     │
               │               empty        │     │ failure
               ▼                            ▼     │
     ┌───────────────────┐              ┌───────────────────┐
     │      Joining      │◀─────────────│      Locking      │
     └───────────────────┘    success   └───────────────────┘
               │
 member-joined │
               │
               ▼
     ┌───────────────────┐
     │    AddingSelf     │
     └───────────────────┘
               │
               │
               ▼
     ┌───────────────────┐
     │    Refreshing     │
     └───────────────────┘
```

If something goes wrong, e.g. a timeout when interacting with etcd, ConstructR by default stops the `ActorSystem`. This can be changed by providing a custom `SupervisorStrategy` to the manually started `Constructr` actor (see below), but be sure you know what you are doing.

## Getting ConstructR

ConstructR is published to Bintray and Maven Central.

``` scala
// All releases including intermediate ones are published here,
// final ones are also published to Maven Central.
resolvers += Resolver.bintrayRepo("hseeberger", "maven")

libraryDependencies ++= List(
  "de.heikoseeberger" %% "constructr" % "0.2.0",
  ...
)
```

## Usage

Simply add the `ConstructrExtension` to the `extensions` configuration setting:

```
akka.extensions = ["de.heikoseeberger.constructr.ConstructrExtension"]
```

This will start the `Constructr` actor as a system actor. Alternatively start it yourself as early as possible if you feel so inclined.

## Configuration

The following listing shows the available configuration settings with their defaults:

```
constructr {
  etcd {
    host    = "localhost"
    host    = ${?CONSTRUCTR_ETCD_HOST}
    port    = 2379
    port    = ${?CONSTRUCTR_ETCD_PORT}
    timeout = 5 seconds // Allow for log compaction or other delays – we're not in a hurry here ;-)
  }

  join-timeout          = 10 seconds // Might depend on cluster size and network properties
  refresh-interval      = 30 seconds // TTL is refresh-interval * ttl-factor
  retry-get-nodes-delay = 2 seconds  // Retry only makes sense if first member has joined and added self, i.e. related to join-timeout
  ttl-factor            = 1.25       // Must be greater than one!
}
```

## Testing

Requirements:
  - `docker` and `docker-machine` have to be installed, e.g. via the [Docker Toolbox](https://www.docker.com/docker-toolbox)
  - A Docker machine named "default" has to be stated, e.g. via `docker-machine start default`
  - The Docker environment has to be set up, e.g. via `eval "$(docker-machine env default)"` 

## Contribution policy ##

Contributions via GitHub pull requests are gladly accepted from their original author. Along with any pull requests, please state that the contribution is your original work and that you license the work to the project under the project's open source license. Whether or not you state this explicitly, by submitting any copyrighted material via pull request, email, or other means you agree to license the material under the project's open source license and warrant that you have the legal authority to do so.

## License ##

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
