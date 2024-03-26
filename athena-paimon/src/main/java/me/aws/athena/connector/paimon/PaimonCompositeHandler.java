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

import com.amazonaws.athena.connector.lambda.handlers.CompositeHandler;
import org.apache.paimon.catalog.Catalog;

import java.util.Map;

public class PaimonCompositeHandler extends CompositeHandler implements ConnectorBase{

    private static Map<String, String> envs = System.getenv();
    private static Catalog catalog = ConnectorBase.buildCatalog(envs);
    public PaimonCompositeHandler() {
        super(new PaimonMetadataHandler(envs, catalog),
                new PaimonRecordHandler(envs, catalog));
    }
}
