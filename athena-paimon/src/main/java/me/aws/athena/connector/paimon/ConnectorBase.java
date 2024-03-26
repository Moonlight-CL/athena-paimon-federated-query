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

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.fs.Path;
import org.apache.paimon.options.CatalogOptions;
import org.apache.paimon.options.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Map;

public interface ConnectorBase {

    static Logger logger = LoggerFactory.getLogger(ConnectorBase.class);

    @Nullable
    static Catalog buildCatalog(Map<String, String> config) {
        if (config == null || config.isEmpty()) {
            return null;
        }

        String dataBucket = config.getOrDefault(Constants.DATA_BUCKET_KEY, "");
        String dataPrefix = config.getOrDefault(Constants.DATA_PREFIX_KEY, "/");

        logger.info("buildCatalog: dataBucket: {}, dataPrefix:{}", dataBucket, dataPrefix);

        if (dataBucket.isEmpty()) {
            return null;
        }

        dataPrefix = dataPrefix.length() > 1 && dataPrefix.startsWith("/") ?
                dataPrefix.substring(0, dataPrefix.length() - 1) : dataPrefix;
        dataPrefix = dataPrefix.endsWith("/") ? dataPrefix : dataPrefix + "/";
        String wareHouse = String.format("s3://%s/%s", dataBucket, dataPrefix);

        Options ops = new Options(config);
        ops.set(CatalogOptions.WAREHOUSE, wareHouse);


        if (ops.containsKey(Constants.AWS_ACCESS_KEY_KEY)) {
            ops.set(Constants.HADOOP_AWS_ACCESS_KEY_KEY, config.get(Constants.AWS_ACCESS_KEY_KEY));
            ops.remove(Constants.AWS_ACCESS_KEY_KEY);
        }

        if (ops.containsKey(Constants.AWS_SECRET_KEY_KEY)) {
            ops.set(Constants.HADOOP_AWS_SECRET_KEY_KEY, config.get(Constants.AWS_SECRET_KEY_KEY));
            ops.remove(Constants.AWS_SECRET_KEY_KEY);
        }

        if (ops.containsKey(Constants.AWS_SESSION_TOKEN_KEY)) {

            String token = config.get(Constants.AWS_SESSION_TOKEN_KEY);
            if (!"".equals(token)) {
                ops.set(Constants.HADOOP_AWS_SESSION_TOKEN_KEY, token);
            }

            ops.remove(Constants.AWS_SESSION_TOKEN_KEY);
        }

        CatalogContext cc = CatalogContext.create(ops);

        return CatalogFactory.createCatalog(cc);
    }

}
