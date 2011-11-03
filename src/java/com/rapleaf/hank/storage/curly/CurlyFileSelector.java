/**
 *  Copyright 2011 Rapleaf
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.rapleaf.hank.storage.curly;

import com.rapleaf.hank.storage.cueball.CueballFileSelector;
import org.apache.log4j.Logger;

import java.util.List;
import java.util.Set;

public class CurlyFileSelector extends CueballFileSelector {
  private static final Logger LOG = Logger.getLogger(CurlyFileSelector.class);

  @Override
  public boolean isRelevantFile(String fileName,
      Integer fromVersion,
      int toVersion, Set<Integer> excludeVersions)
  {
    if (fileName.matches(Curly.BASE_REGEX) || fileName.matches(Curly.DELTA_REGEX)) {
      int ver = Curly.parseVersionNumber(fileName);
      if ((fromVersion == null || ver > fromVersion) && ver <= toVersion) {
        return true;
      }
    } else {
      if (LOG.isTraceEnabled()) {
        LOG.trace(String.format("%s is not relevant for update %s -> %s (to curly)", fileName, fromVersion, toVersion));
      }
    }

    return super.isRelevantFile(fileName, fromVersion, toVersion, excludeVersions);
  }

  @Override
  public List<String> selectFilesToCopy(List<String> relevantFiles,
      Integer fromVersion,
      int toVersion, Set<Integer> excludeVersions)
  {
    return super.selectFilesToCopy(relevantFiles, fromVersion, toVersion, excludeVersions);
  }

  @Override
  protected boolean isBase(String path) {
    if (path.matches(Curly.BASE_REGEX)) {
      return true;
    }
    return super.isBase(path);
  }

  @Override
  protected int parseVersion(String path) {
    if (path.matches(Curly.BASE_REGEX) || path.matches(Curly.DELTA_REGEX)) {
      return Curly.parseVersionNumber(path);
    }
    return super.parseVersion(path);
  }
}
