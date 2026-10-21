# High Availabilty

As v1.29.1, Maxwell contains experiemental (alpha quality) client-side HA.
Support for performing leader elections is done via
[jgroups-raft](https://github.com/belaban/jgroups-raft).

## Getting started

First, copy `raft.xml.example` to `raft.xml`.  Edit to your liking, paying attention to:

```
    <raft.RAFT members="A,B,C" raft_id="${raft_id:undefined}"/>
```

Note that because we are using a RAFT-based leader election, we will have to spin up at least
3 maxwell client nodes.

The example stack talks TCP to a static list of peers (`TCPPING`), encrypts all
cluster traffic with `SYM_ENCRYPT` and requires each joining node to pass `AUTH`
(`X509Token`).  Whichever node wins the election runs replication, so a host that
could join the cluster unchallenged could hold leadership and stop CDC, or force
repeated elections that start and stop Maxwell.  Don't fall back to an
unauthenticated UDP multicast stack.

### Create the shared keystore

Every node uses the same PKCS12 keystore, holding an AES key for `SYM_ENCRYPT`
and an RSA key pair for `AUTH`:

```bash
keytool -genseckey -alias maxwell-raft-sym -keyalg AES -keysize 256 \
        -storetype PKCS12 -keystore raft.p12
keytool -genkeypair -alias maxwell-raft-auth -keyalg RSA -keysize 2048 \
        -validity 3650 -dname "CN=maxwell-raft" -storetype PKCS12 -keystore raft.p12
```

Copy `raft.p12` to each node over a secure channel and make it readable only by
the user running Maxwell (`chmod 600`).  Treat the keystore, its password and the
`AUTH` value as secrets.

### Start the nodes

The keystore password and the `AUTH` shared value are read from the
`MAXWELL_RAFT_KEYSTORE_PASSWORD` and `MAXWELL_RAFT_AUTH_VALUE` environment
variables, so that no secrets live in `raft.xml`.  If either is missing the node
fails to start.  Don't pass secrets as `-D` JVM arguments: command lines are
visible to every local user (`ps`), whereas a process's environment is only
readable by its owner and root.  Load them from a file only the Maxwell user can
read, e.g. a systemd `EnvironmentFile=` or:

```bash
# /etc/maxwell/raft.env, mode 0600, owned by the maxwell user
MAXWELL_RAFT_KEYSTORE_PASSWORD=...
MAXWELL_RAFT_AUTH_VALUE=...
```

The keystore location and the peer list are not secret and are passed as JVM
system properties:

```bash
set -a; . /etc/maxwell/raft.env; set +a
export JAVA_OPTS="-Dmaxwell.raft.keystore=/etc/maxwell/raft.p12 \
  -Djgroups.tcpping.initial_hosts=host1[7800],host2[7800],host3[7800] \
  -Djava.net.preferIPv4Stack=true"

host1: $ bin/maxwell --ha --raft_member_id=A
host2: $ bin/maxwell --ha --raft_member_id=B
host3: $ bin/maxwell --ha --raft_member_id=C
```

`-Djgroups.bind_addr` (default: a site-local address) and `-Djgroups.bind_port`
(default `7800`) choose the address each node listens on.

If all goes well, the 3 nodes will find each other over TCP, elect one to be the
cluster leader, and away you will go.  If one node is terminated or partitioned,
a new election will be held to replace it.  A node without the cluster key can't
decrypt cluster traffic and never joins; a node with the wrong `AUTH` value is
rejected with `authentication failed`.

### Isolate the cluster network

Encryption and `AUTH` don't replace network controls.  Run HA nodes on a
private network or VLAN, and firewall the bind port (`7800` by default) so only
the other Maxwell HA nodes can reach it.  Anyone holding the keystore and its
password can join the cluster, so rotate both if a node is retired or
compromised.


## Getting deeper

More advanced (especially inter-DC) configurations may be implemented by
editing `raft.xml`, e.g. replacing `SYM_ENCRYPT` with `ASYM_ENCRYPT` +
`SSL_KEY_EXCHANGE` so that group keys are distributed over mutual TLS and rotated
when members leave.  Keep encryption and `AUTH` in any stack you build.  See the
[jgroups documentation](http://www.jgroups.org/manual5/index.html#Security)
for details, and if you come up with something good drop me a line.

## Common problems

With the old multicast configuration, something I encountered right out of the gate was this:

```
12:37:53,135 WARN  UDP - failed to join /224.0.75.75:7500 on utun0: java.net.SocketException: Can't assign requested address
```

which can be worked around by forcing the JVM onto an ipv4 stack:

```
JAVA_OPTS="-Djava.net.preferIPv4Stack=true" bin/maxwell --ha --raft_member_id=B
```






