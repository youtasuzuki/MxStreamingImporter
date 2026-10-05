# Description
StreamingImporter was created to enable the high-speed, memory-efficient import of data—such as XLSX or CSV files—into Mendix entities with a simple implementation. (As of version 1.0.0, only XLSX is supported.)  
Please explore the sample implementation included in the StreamingImporter module to see how these features deliver simplicity, high performance, and ease of operation.

## Key Features
- Lightweight, flexible, and refactor-proof column mapping.
- Ultra-fast streaming processing.

## Refactor-proof column mapping
This module achieves simple column mapping without the need for additional structures by utilizing a single object of the target entity and setting the values of its String-type attributes to match the header names from the data file (e.g., XLSX).  
Consequently, no extra work is required when changing attribute names in the target entity. There is no need to configure mappings on a dedicated screen, nor is there a need to manage such configurations during release deployments.  
While support for converting data to types other than String has been omitted, standard import implementations typically route data through a "work" entity composed of String fields; thus, type conversion was excluded to maintain simplicity, prioritizing these common use cases.  

### Three column mapping modes
- by explicit designation
- by entity attribute name
- by colmun-attribute order

## Streaming Processing
For XLSX imports, the system utilizes XSSFReader to perform full streaming processing while supporting batch commits and transaction splitting; it achieves a balance between high performance and flexibility by confining potentially slow Microflow operations to callbacks executed prior to batch commits. The decision to handle the read loop within Java—rather than in a Microflow—was made to ensure that XSSFReader’s event processing remains simple, single-threaded, and minimally resource-intensive.

# Restrictions
### As of version 1.0.0, only XLSX is supported.

# Dependencies
### poi-ooxml
