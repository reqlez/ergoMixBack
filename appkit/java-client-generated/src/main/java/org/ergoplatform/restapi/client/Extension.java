/*
 * Ergo Node API
 * API docs for Ergo Node. Models are shared between all Ergo products
 *
 * OpenAPI spec version: 0.1
 * Contact: ergoplatform@protonmail.com
 *
 * NOTE: This class is auto generated by the swagger code generator program.
 * https://github.com/swagger-api/swagger-codegen.git
 * Do not edit the class manually.
 */

package org.ergoplatform.restapi.client;

import java.util.Objects;

import com.google.gson.annotations.SerializedName;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * Extension
 */

@javax.annotation.Generated(value = "io.swagger.codegen.v3.generators.java.JavaClientCodegen", date = "2019-10-19T14:53:04.559Z[GMT]")
public class Extension {
  @SerializedName("headerId")
  private String headerId = null;

  @SerializedName("digest")
  private String digest = null;

  @SerializedName("fields")
  private List<KeyValueItem> fields = new ArrayList<>();

  public Extension headerId(String headerId) {
    this.headerId = headerId;
    return this;
  }

   /**
   * Get headerId
   * @return headerId
  **/
  @Schema(required = true, description = "")
  public String getHeaderId() {
    return headerId;
  }

  public void setHeaderId(String headerId) {
    this.headerId = headerId;
  }

  public Extension digest(String digest) {
    this.digest = digest;
    return this;
  }

   /**
   * Get digest
   * @return digest
  **/
  @Schema(required = true, description = "")
  public String getDigest() {
    return digest;
  }

  public void setDigest(String digest) {
    this.digest = digest;
  }

  public Extension fields(List<KeyValueItem> fields) {
    this.fields = fields;
    return this;
  }

  public Extension addFieldsItem(KeyValueItem fieldsItem) {
    this.fields.add(fieldsItem);
    return this;
  }

   /**
   * List of key-value records
   * @return fields
  **/
  @Schema(required = true, description = "List of key-value records")
  public List<KeyValueItem> getFields() {
    return fields;
  }

  public void setFields(List<KeyValueItem> fields) {
    this.fields = fields;
  }


  @Override
  public boolean equals(java.lang.Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Extension extension = (Extension) o;
    return Objects.equals(this.headerId, extension.headerId) &&
        Objects.equals(this.digest, extension.digest) &&
        Objects.equals(this.fields, extension.fields);
  }

  @Override
  public int hashCode() {
    return Objects.hash(headerId, digest, fields);
  }


  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class Extension {\n");
    
    sb.append("    headerId: ").append(toIndentedString(headerId)).append("\n");
    sb.append("    digest: ").append(toIndentedString(digest)).append("\n");
    sb.append("    fields: ").append(toIndentedString(fields)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(java.lang.Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }

}
