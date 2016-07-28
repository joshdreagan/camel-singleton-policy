/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.examples.singleton;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.camel.Exchange;
import org.apache.camel.FailedToStartRouteException;
import org.apache.camel.Route;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.Service;
import org.apache.camel.spi.RoutePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SingletonRoutePolicy implements RoutePolicy, Service {

  private static final Logger log = LoggerFactory.getLogger(SingletonRoutePolicy.class);

  private final File file;
  private long pollInterval = 10000L;
  private long validationInterval = 1000L;

  private RandomAccessFile _raf;
  private FileLock _lock;
  private ExecutorService _pool;

  public SingletonRoutePolicy(File file) {
    this.file = file;
  }

  public File getFile() {
    return file;
  }

  public long getPollInterval() {
    return pollInterval;
  }

  public void setPollInterval(long pollInterval) {
    this.pollInterval = pollInterval;
  }

  public long getValidationInterval() {
    return validationInterval;
  }

  public void setValidationInterval(long validationInterval) {
    this.validationInterval = validationInterval;
  }

  private void doStart() throws Exception {
    if (!file.exists()) {
      file.createNewFile();
    }
    if (_raf == null) {
      _raf = new RandomAccessFile(file, "rws");
    }
    if (_pool == null || _pool.isShutdown() || _pool.isTerminated()) {
      _pool = Executors.newCachedThreadPool();
    }
  }

  private void doStop() throws Exception {
    if (_pool != null) {
      _pool.shutdown();
    }
    if (_lock != null) {
      _lock.release();
    }
    if (_raf != null) {
      _raf.close();
    }
  }

  @Override
  public void onInit(Route route) {
  }

  @Override
  public void onRemove(Route route) {
  }

  @Override
  public void onStart(Route route) {
    try {
      doStart();
      log.debug(String.format("Acquiring lock for route: [%s].", route.getId()));
      _lock = _raf.getChannel().tryLock();
      if (_lock == null || !_lock.isValid()) {
        log.debug(String.format("Unable to acquire lock for route: [%s].", route.getId()));
        stopConsumer(route);
      }
      awaitLockAndStartConsumer(route);
    } catch (Exception e) {
      throw new RuntimeCamelException(new FailedToStartRouteException(e));
    }
  }

  @Override
  public void onStop(Route route) {
    try {
      doStop();
    } catch (Exception e) {
      throw new RuntimeCamelException(e);
    }
  }

  @Override
  public void onSuspend(Route route) {
    onStop(route);
  }

  @Override
  public void onResume(Route route) {
    onStart(route);
  }

  @Override
  public void onExchangeBegin(Route route, Exchange exchange) {
  }

  @Override
  public void onExchangeDone(Route route, Exchange exchange) {
  }

  @Override
  public void start() throws Exception {
    doStart();
  }

  @Override
  public void stop() throws Exception {
    doStop();
  }

  private void startConsumer(Route route) {
    try {
      if (route.getConsumer() != null) {
        log.debug(String.format("Starting consumer for route: [%s].", route.getId()));
        route.getConsumer().start();
      }
    } catch (Exception e) {
      throw new RuntimeCamelException("Unable to start consumer.", e);
    }
  }

  private void stopConsumer(Route route) {
    try {
      if (route.getConsumer() != null) {
        log.debug(String.format("Stopping consumer for route: [%s].", route.getId()));
        route.getConsumer().stop();
      }
    } catch (Exception e) {
      throw new RuntimeCamelException("Unable to stop consumer.", e);
    }
  }

  private void awaitLockAndStartConsumer(Route route) {
    _pool.submit(new Runnable() {
      @Override
      public void run() {
        while (!_pool.isShutdown() && (_lock == null || !_lock.isValid())) {
          log.debug(String.format("Acquiring lock for route: [%s].", route.getId()));
          try {
            _lock = _raf.getChannel().lock();
          } catch (IOException e) {
          }
          if (_lock != null && _lock.isValid()) {
            try {
              startConsumer(route);
              monitorLockAndStopConsumer(route);
            } catch (Exception e) {
              log.error(String.format("Caught an exception while starting consumer for route: [%s].  Will try again in [%s] seconds.", route.getId(), pollInterval), e);
            }
          } else {
            log.debug(String.format("Unable to acquire lock for route: [%s]. Will try again in [%s] seconds.", route.getId(), pollInterval));
            try {
              Thread.sleep(pollInterval);
            } catch (InterruptedException e) {
            }
          }
        }
      }
    });
  }

  private void monitorLockAndStopConsumer(Route route) {
    _pool.submit(new Runnable() {
      @Override
      public void run() {
        while (!_pool.isShutdown() && _lock != null && _lock.isValid()) {
          try {
            Thread.sleep(validationInterval);
          } catch (InterruptedException e) {
          }
        }
        log.debug(String.format("Lost lock for route: [%s].", route.getId()));
        try {
          stopConsumer(route);
        } catch (Exception e) {
          log.error(String.format("Caught an exception while stopping consumer for route: [%s].", route.getId()), e);
        }
        awaitLockAndStartConsumer(route);
      }
    });
  }
}
