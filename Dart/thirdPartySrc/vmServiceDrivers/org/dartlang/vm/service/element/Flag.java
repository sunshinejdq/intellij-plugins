/*
 * Copyright (c) 2015, the Dart project authors.
 *
 * Licensed under the Eclipse Public License v1.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.dartlang.vm.service.element;

// This is a generated file.

import com.google.gson.JsonObject;

/**
 * A {@link Flag} represents a single VM command line flag.
 */
@SuppressWarnings({"WeakerAccess", "unused", "UnnecessaryInterfaceModifier"})
public class Flag extends Element {

  public Flag(JsonObject json) {
    super(json);
  }

  /**
   * A description of the flag.
   */
  public String getComment() {
    return json.get("comment").getAsString();
  }

  /**
   * Has this flag been modified from its default setting?
   */
  public boolean getModified() {
    return json.get("modified").getAsBoolean();
  }

  /**
   * The name of the flag.
   */
  public String getName() {
    return json.get("name").getAsString();
  }

  /**
   * The value of this flag as a string.
   *
   * If this property is absent, then the value of the flag was NULL.
   */
  public String getValueAsString() {
    return json.get("valueAsString").getAsString();
  }
}
