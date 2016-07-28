#Camel Singleton Policy

This is an example `org.apache.camel.spi.RoutePolicy` implementation that allows you to use a lock file to ensure a single instance of a route running in a cluster.

##Requirements

- [Apache Maven 3.x](http://maven.apache.org)

##Building Example

Run the Maven build

```
~$ cd $PROJECT_ROOT
~$ mvn clean install
```

##Running Camel

Open up two terminal windows.

Terminal 1:

```
~$ cd $PROJECT_ROOT
~$ mvn -DlockFile=/tmp/singletonTimer.lck -DinstanceId=instance01 camel:run
```

Terminal 2:

```
~$ cd $PROJECT_ROOT
~$ mvn -DlockFile=/tmp/singletonTimer.lck -DinstanceId=instance02 camel:run
```

##Testing

You should see log statements from the timer route in only one terminal at a time. You can kill the currently active process and watch the other one start and begin logging messages.