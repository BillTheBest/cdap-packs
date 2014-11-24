/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.lib.etl.schema;

/**
 * Defines field in data record {@link Schema}.
 */
public class Field {
  private final String name;
  private final FieldType type;

  public Field(String name, FieldType type) {
    this.name = name;
    this.type = type;
  }

  public String getName() {
    return name;
  }

  public FieldType getType() {
    return type;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    Field field = (Field) o;

    if (name != null ? !name.equals(field.name) : field.name != null) return false;
    if (type != field.type) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = name != null ? name.hashCode() : 0;
    result = 31 * result + (type != null ? type.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    final StringBuffer sb = new StringBuffer("Field{");
    sb.append("name='").append(name).append('\'');
    sb.append(", type=").append(type);
    sb.append('}');
    return sb.toString();
  }
}
