/*-
 * #%L
 * athena-paimon
 * %%
 * Copyright (C) 2019 - 2024 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package me.aws.athena.connector.paimon;

public class Constants {

    public static final String SOURCE_TYPE = "paimon";

    public static String WAREHOUSE_KEY = "warehouse";

    public static String DATA_BUCKET_KEY = "paimon_data_bucket";

    public static String DATA_PREFIX_KEY = "paimon_data_prefix";

//    public static String AWS_SESSION_TOKEN_KEY = "s3.session.token";

    public static String AWS_ACCESS_KEY_KEY = "access_key";
    public static String HADOOP_AWS_ACCESS_KEY_KEY = "s3.access-key";
    public static String AWS_SECRET_KEY_KEY = "secret_key";
    public static String HADOOP_AWS_SECRET_KEY_KEY = "s3.secret-key";
    public static String AWS_SESSION_TOKEN_KEY = "session_token";
    public static String HADOOP_AWS_SESSION_TOKEN_KEY = "s3.session.token";
}
